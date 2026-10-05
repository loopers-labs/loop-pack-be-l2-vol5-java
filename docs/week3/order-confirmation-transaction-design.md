# 주문 확정 — 트랜잭션 설계

대상은 `POST /api/v1/orders/{orderId}/confirm`의 최초 확정이다. 주문 소유자와 상태를 확인하고 재고·포인트를 차감한 뒤 결제 결과를 저장한다.

## 1. 트랜잭션 경계와 흐름

`OrderFacade.confirm()` 한 번의 실행에서 요청자·주문 검증부터 확정 저장까지 같은 DB 트랜잭션으로 처리한다. 전체가 성공하면 commit하고, 중간 오류나 버전 충돌이 나면 해당 시도의 변경을 모두 rollback한다.

```text
OrderV1Controller.confirm(): X-USER-ID 확인
  → OrderRetryCoordinator.confirm(): 트랜잭션 밖
    → 주입된 OrderFacade 프록시: 시도별 트랜잭션 시작
      → OrderFacade.confirm()
        1. UserValidator.validateExists(): 요청자 존재 여부 확인
        2. OrderRepository.findById(): Order 조회
        3. 요청자 ID와 Order 소유자 비교, Order.validateDraft(): 최초 확정 가능 여부 확인
        4. 저장된 OrderItem에서 상품별 총수량 계산·검증
        5. productId 오름차순으로 Product 조건부 차감 UPDATE 실행
           영향 행 수를 확인하고, 실패하면 예외로 전체 rollback
        6. Point 조회 → 잔액 확인 → 차감 반영 및 저장
        7. Order.confirm(): CONFIRMED 상태와 결제 결과 반영
    ← Facade 프록시: flush·commit 또는 rollback 후 예외 전파
  ← Coordinator: 성공 반환 또는 버전 충돌 재시도
← Controller 성공 응답 / ApiControllerAdvice 오류 응답
```

재고 차감, 포인트 차감, Order의 `CONFIRMED` 상태와 결제 결과는 함께 반영한다. 실패하면 주문은 DRAFT로 남고 재고·잔액·결제 결과도 변경 전 상태를 유지한다. 이미 확정된 주문의 재요청은 기존 상태 오류 `409`이며 성공 응답을 재사용하지 않는다.

OrderRetryCoordinator 진입 시 활성 트랜잭션이 없어야 한다. 별도 Spring bean인 Facade 프록시를 매 시도 외부 호출해 flush와 commit까지 포함한 트랜잭션을 끝낸 뒤 다음 시도를 시작한다. 전파는 `REQUIRED`, 격리 설정은 `DEFAULT`다. Order·Point 저장과 Product 조건부 UPDATE는 같은 DB와 트랜잭션 관리자를 사용한다. Repository는 구현체에서 Spring Data JPA 프록시를 거쳐 DB에 접근한다.

## 2. 동시성 보호 전략

| 대상 | 전략 | 보호하는 경쟁 |
|---|---|---|
| Order | JPA 엔티티의 `@Version` | 같은 주문을 동시에 확정하는 요청의 오래된 상태 저장 |
| Product 재고 | `deleted_at IS NULL`, `stock >= :quantity` 조건부 UPDATE | 서로 다른 주문의 재고 차감, 삭제와 차감의 경합 |
| Point 잔액 | JPA 엔티티의 `@Version` | 같은 사용자의 주문 확정과 포인트 충전 |

Order는 `validateDraft()`와 `confirm()`으로 상태 전이를 책임진다. Point는 잔액 검사와 차감을, Product는 재고 규칙을 담당한다. Application이 세 대상을 조회하고 협력시킨다. User는 존재 여부와 주문 소유자 비교에 사용하며 별도 잠금 대상은 두지 않는다.

재고 차감은 현재 재고 검사와 차감을 한 SQL로 처리한다. 영향 행 수가 1이면 성공, 0이면 상품 부재·삭제 또는 재고 부족이므로 주문 확정 전체를 `409`로 거절한다.

```sql
UPDATE products
SET stock = stock - :quantity,
    updated_at = :updatedAt
WHERE id = :productId
  AND deleted_at IS NULL
  AND stock >= :quantity;
```

상품 수정·재고 설정·단독 삭제·브랜드 일괄 삭제도 `deleted_at IS NULL` 조건으로 기능별 컬럼을 갱신한다. 재고 차감과 최종 재고 설정은 DB가 직렬화한 쓰기 순서에 따라 반영된다. 조건부 UPDATE 뒤 오래된 Product 객체를 다시 저장하지 않는다.

Order와 Point의 `@Version`은 Infrastructure JPA 엔티티에 둔다. 충전과 주문 차감은 같은 Point 버전 검사에 참여한다. 낙관적 잠금은 오래된 변경을 감지하며, 재고 부족 판정이나 멱등성 자체를 대신하지 않는다. InnoDB UPDATE의 배타 잠금 대기는 남고, 여러 상품은 ID 오름차순으로 처리해 교착 위험을 줄인다.

## 3. 예외와 재시도

Order 또는 Point의 낙관적 버전 충돌만 재시도한다. 실패한 시도를 rollback하고 끝낸 다음, 새 트랜잭션에서 주문 확정 전체를 다시 수행한다. 요청자와 주문 상태를 다시 확인하고 Order·Point를 새로 조회하며 모든 상품의 재고 차감도 다시 판단한다. 이전 엔티티나 버전을 재사용하지 않는다. Coordinator는 Facade 프록시 호출 전체를 감싸 flush·commit 단계에서 발생한 버전 충돌도 처리한다.

| 결과 | 처리 |
|---|---|
| 식별값 누락 | 기존 `400`, 변경 없음 |
| User/Order 없음 또는 타인 주문 | 기존 `404`, 재시도 없음 |
| Order가 DRAFT 아님, 잔액 부족, 재고 조건부 차감 실패 | 기존 업무 오류 `409`, 전체 rollback, 재시도 없음 |
| Order/Point 버전 충돌 | 전체 rollback 후 제한적으로 새 트랜잭션에서 재시도 |
| 재시도 횟수까지 버전 충돌 지속 | 충돌 오류 `409` |
| 그 밖의 저장·flush·commit 오류, 교착·잠금 시간 초과 | 전체 rollback, 기존 기술 오류 처리; 버전 충돌 재시도에 포함하지 않음 |

`maxAttempts = 2`는 최초 실행 1회와 추가 재시도 최대 1회를 뜻한다. 충돌이 드물다는 현재 가정에서 최신 상태로 다시 판단할 기회를 한 번 주되, 전체 유스케이스를 반복하는 DB 작업량은 제한하려는 선택이다. 충돌 빈도나 재시도 성공률을 측정한 최적값은 아니다.

추가 대기는 `0ms`다. 실패한 트랜잭션이 종료되고 자원이 반환된 뒤 즉시 새 트랜잭션을 시작한다. 같은 DB 안에서 짧게 끝나는 유스케이스이며 외부 호출이 없어 별도 대기가 응답 시간만 늘릴 수 있다고 판단했다. 이 대기는 DB가 SQL 실행 중 거는 잠금 대기와 별개다.

재고 부족, 포인트 잔액 부족, 이미 확정된 주문은 해당 시도에서 판정된 업무 거절이므로 서버가 자동 반복하지 않고 기존 `409`를 반환한다. 이번 재시도 정책은 오래된 상태를 다시 읽으면 해소될 수 있는 낙관적 버전 충돌만 대상으로 한다. 교착·잠금 시간 초과와 그 밖의 기술 오류는 별도 재시도 정책을 두지 않고 기존 기술 오류로 처리한다. 최대 횟수까지 버전 충돌이 이어지면 충돌 오류 `409`로 종료한다.

## 4. 검증 기준

- 본인 소유의 DRAFT만 확정된다. 동시 확정 요청은 성공이 한 번만 반영되고, 이후 요청은 추가 차감 없이 상태 오류를 받는다.
- 중복 품목은 상품별 총수량으로 재고를 검사한다. 재고 부족·상품 삭제·포인트 부족이면 주문은 DRAFT로 남고 모든 재고·잔액·결제 결과가 보존된다.
- 서로 다른 사용자의 동시 주문에서 `초기 재고 − 성공 주문 수량 합 = 최종 재고`가 성립한다. 같은 사용자의 충전과 주문 확정 경쟁에서 `초기 잔액 + 성공 충전액 − 성공 결제액 = 최종 잔액`이 성립한다.
- 중간 실패 테스트는 실제 재고 UPDATE 이후 실패를 발생시키고, 트랜잭션 종료 뒤 새 DB 경계에서 Order·Product·Point를 조회해 rollback을 확인한다. 다른 요청의 commit은 보존한다.
- 버전 충돌 재시도는 전체 유스케이스가 새 트랜잭션에서 실행되는지 확인한다. 재시도 끝의 업무 거절, 충돌 지속, 기술 오류를 서로 구분한다.
- 상품별 UPDATE는 `productId` 오름차순 실행을 확인한다. JPA의 자동 flush 시점과 실제 SQL 순서는 저장 호출 순서와 같다고 가정하지 않고 DB 통합 테스트에서 확인한다.
