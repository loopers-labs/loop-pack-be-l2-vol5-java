# 브랜드 일괄 삭제와 비노출 — 트랜잭션 설계

대상은 `DELETE /api-admin/v1/brands/{brandId}`의 브랜드와 연결된 상품 일괄 논리 삭제다.

## 1. 트랜잭션 범위

| 구분 | 설계                                                                           |
|---|------------------------------------------------------------------------------|
| 경계 | `BrandFacade.delete()` 한 번의 실행을 한 DB 트랜잭션으로 처리                               |
| 함께 변경 | Brand와 연결된 미삭제 Product 전체의 `deletedAt`<br/>재고 0인 상품 포함, 연결 상품이 없으면 Brand만 삭제 |
| 보존 | 기삭제 Product의 삭제 시각, 다른 Brand, Product, 재고, 기존 Like, 주문 정보, 포인트 결제 결과         |
| 성공 | 전체 변경을 commit한 뒤 `200 OK` 반환                                                 |
| 실패 | 해당 시도의 변경 전체 rollback. 다른 요청의 commit은 보존                                     |

## 2. 호출 경로와 처리 순서

```text
관리자 요청 → Security FilterChain → BrandV1Controller.delete()
  → BrandRetryCoordinator.delete(): 트랜잭션 밖
    → 주입된 BrandFacade 프록시: 트랜잭션 시작
      → BrandFacade.delete()
        1. BrandRepository.findActiveById(): Brand 조회 후 존재 여부 검증
        2. ProductRepository.findActiveByBrandId(): 미삭제 Product를 ID 오름차순 조회
        3. ProductDeletionPolicy → BrandDeletionPolicy: 각 대상의 삭제 상태 변경
        4. Product들 → Brand 저장: 매퍼로 JPA 엔티티에 변경 반영
    ← Facade 프록시: flush 후 commit, 실패 시 rollback 후 예외 전파
  ← Coordinator: 성공 반환 또는 버전 충돌 재시도
← Controller 성공 응답 / ApiControllerAdvice 최종 오류 응답
```

Repository 호출은 **구현체와 매퍼 → Spring Data JPA Repository 프록시 → DB**로 이어진다. Brand와 Product는 별도 Aggregate이며 Facade가 두 삭제 Policy를 조합한다. 조회부터 저장까지 같은 DB, 트랜잭션 관리자, 영속성 컨텍스트를 사용한다.

전파는 `REQUIRED`, 격리 설정은 `DEFAULT`다. **Coordinator 진입 시 활성 트랜잭션이 없어야 하며**, 별도 Spring bean인 Facade 프록시를 시도마다 외부 호출한다. 바깥 트랜잭션이 있으면 `REQUIRED`가 그 경계에 참여해 시도별 종료와 재시도가 깨질 수 있다.

## 3. 예외 처리와 재시도

| 결과 | 처리 |
|---|---|
| Brand가 없거나 이미 삭제됨 | `CoreException(NOT_FOUND)` → `404`, 재시도 없음 |
| Brand 또는 Product에서 버전 충돌, 남은 시도 횟수 있음 | 해당 시도 전체를 rollback하고 종료한 뒤 새 트랜잭션에서 재시도 |
| 최대 시도 횟수까지 모두 버전 충돌 | `CoreException(CONFLICT)` → `409`, 이번 요청의 변경 없음 |
| 저장, flush, commit 과정의 기술 오류 | 해당 시도 전체 rollback, `500`, 버전 충돌 재시도에 포함하지 않음 |

RuntimeException, Error는 Facade 밖으로 전파한다. Coordinator는 **Facade 프록시 호출 전체**를 감싸 commit 과정에서 발생한 버전 충돌까지 처리한다. 업무 오류, 교착, 잠금 시간 초과를 버전 충돌로 분류하지 않는다.

재시도는 **전체 rollback 후 트랜잭션 종료 → 새 트랜잭션과 영속성 컨텍스트 시작 → Brand와 대상 Product 전체 재조회 후 검증 → 삭제 명령 재적용 → commit** 순서다. 재조회에서 Brand가 없거나 삭제됐다면 `404`로 종료한다.<br>실패한 객체를 재사용하거나 버전만 최신 값으로 바꾸지 않는다. `open-in-view: false`를 유지한다.

`maxAttempts`는 **최초 시도와 추가 재시도를 합친 최대 실행 횟수**다. 예를 들어 `maxAttempts = 3`이면 최초 시도 1회와 추가 재시도 최대 2회이며, 3회 모두 버전 충돌이면 재시도를 중단하고 `409`로 응답한다.<br>최대 시도 횟수와 대기 간격은 결정 항목이며, 대기를 적용하면 실패한 트랜잭션 종료 후 수행한다.

자기 호출로 경계를 만들거나, 예외를 삼켜 일부 성공으로 반환하거나, 상품별 `REQUIRES_NEW`로 독립 commit하지 않는다.

## 4. 동시성 보호

**같은 행의 갱신 충돌이 드물고, 삭제 명령을 최신 상태에 다시 적용할 수 있다는 전제**에서 낙관적 잠금을 선택한다. 대상 상품이 많거나 주문 차감과 자주 겹치면 전체 삭제를 재시도하는 비용을 기준으로 전략을 재검토한다.

| 보호할 행 | 전략과 이유 |
|---|---|
| Brand | `@Version`. 같은 Brand의 이름 수정과 삭제가 겹칠 때 오래된 저장이 삭제 상태나 새 이름을 덮어쓰지 않도록 보호 |
| 대상 Product | `@Version`. 같은 Product의 수정, 재고 설정, 주문 차감에서 성공한 변경을 오래된 삭제 저장이 덮어쓰지 않도록 보호 |

공유하는 변경 대상이 없는 서로 다른 브랜드의 삭제를 하나의 공통 잠금으로 직렬화하지 않는다.

Brand의 수정과 삭제, Product의 수정과 재고 설정 및 삭제는 처음 조회한 JPA 엔티티의 버전으로 저장을 검사하며, 엔티티 저장의 버전 증가는 JPA 구현체가 관리한다. 삭제를 JPQL bulk UPDATE나 직접 SQL로 처리한다면 버전 검사와 증가를 명시적으로 맞춘다.

주문의 Product 재고 차감은 현재 상태를 조건으로 갱신하고 버전을 직접 증가시켜, 차감 전에 읽은 삭제 작업의 오래된 저장을 거절한다. 같은 행을 갱신하는 모든 경로가 버전 증가에 참여해야 한다. 삭제의 버전 조건을 설명하는 SQL은 다음과 같다.

```sql
UPDATE products
SET deleted_at = :deletedAt, version = version + 1
WHERE id = :productId AND version = :readVersion;
```

- **갱신과 잠금 순서:** Product ID 오름차순을 같은 상품들을 갱신하는 경로의 공통 기준으로 삼는다. 저장 호출 순서와 flush 시 실제 UPDATE 순서는 구분해 확인한다. InnoDB UPDATE의 배타 잠금은 트랜잭션 종료까지 유지되므로, 낙관적 제어에서도 교착을 고려한다.
- **동시 상품 등록:** 새 Product의 INSERT는 Brand나 기존 Product 버전을 자동으로 바꾸지 않는다. 대상 조회 후 새 상품 등록이 commit되면 삭제가 그 상품을 놓칠 수 있다. 삭제된 Brand에 활성 Product가 남지 않도록 등록과 삭제가 같은 Brand의 버전 검사와 증가에 참여하거나 공통 Brand 잠금을 사용하는 방식을 결정해야 한다. 선택한 보호 대상의 획득 순서도 두 경로에서 맞춘다.
- **새 좋아요와 DRAFT 생성:** Product를 읽고 다른 행만 저장하는 요청은 Product의 `@Version`만으로 보호되지 않는다. 삭제 commit 후 시작한 요청은 거절하며, 삭제와 겹친 요청의 성공 또는 거절 기준과 보호 방식은 결정 항목이다.

## 5. 핵심 검증 조건

- **성공과 보존:** Brand와 미삭제 Product 전체가 함께 삭제되고 보존 대상은 유지된다. 재고가 0이거나 연결 상품이 없는 경우도 포함한다.
- **중간 실패:** 첫 실제 변경 SQL 이후 다음 저장에서 테스트 구성으로 예외를 발생시킨다. 서비스 트랜잭션 종료 후 새 DB 조회에서 이번 요청의 변경이 모두 취소됐는지 확인한다.
- **충돌과 재시도:** Brand나 Product 하나의 충돌로 해당 시도 전체가 rollback된다. 재시도는 최신 상태를 사용하고 다른 요청의 commit을 보존한다. 최종 응답은 성공 `200`, 기삭제 `404`, 최대 시도 횟수까지 모두 충돌하면 `409`다.
- **동시 상품 등록:** 등록과 삭제가 겹쳐도 삭제된 Brand에 활성 Product가 남는 결과를 허용하지 않는다.
