# 브랜드·연결 상품 일괄 삭제 — 트랜잭션 설계

대상 API는 관리자 `DELETE /api-admin/v1/brands/{brandId}`다. 요청 하나에서 Brand와 연결된 미삭제 Product 전체를 함께 논리 삭제한다.

## 1. 트랜잭션 경계와 흐름

`BrandFacade.delete()`를 유스케이스 트랜잭션 경계로 둔다. 활성 Brand와 연결된 미삭제 Product를 한 트랜잭션에서 논리 삭제한다.
Product를 먼저 갱신하고 마지막에 Brand를 갱신한다. 두 갱신에는 같은 삭제 시각을 전달한다.

```text
Admin Controller: 관리자 접근 확인
  → BrandFacade 프록시: REQUIRED 트랜잭션 시작
    → 활성 Brand 조회와 배타 잠금 (PESSIMISTIC_WRITE)
       없으면 기존 404
    → Brand에 연결된 미삭제 Product 전체를 조건부 갱신
       영향 행 수 0은 정상
    → Brand를 미삭제 조건으로 논리 삭제
       영향 행 수 1이어야 정상
  ← 전체 성공이면 commit, 업무 예외 또는 처리·flush 실패면 rollback
← Controller 성공 응답 / ApiControllerAdvice 오류 응답
```

Brand 배타 잠금은 Product 갱신과 Brand 갱신을 마치고 트랜잭션이 끝날 때까지 유지한다.
Product 갱신, Brand 갱신 또는 commit 전 flush에서 RuntimeException이 발생하면 Facade 밖으로 전달해 이번 요청의 변경을 모두 rollback한다.
상품마다 별도 트랜잭션을 만들거나 예외를 삼켜 부분 성공으로 반환하지 않는다.

## 2. 동시성 보호 전략

삭제가 성공한 뒤에는 해당 Brand에 활성 Product가 남지 않아야 한다. Product 일괄 갱신만으로는 동시에 실행되는 Product 등록을 막지 못한다.
등록이 Product 갱신 뒤 commit되면 삭제된 Brand 아래 활성 Product가 남을 수 있다.
외래 키는 Brand 행의 존재를 확인하지만 논리 삭제 상태는 검사하지 않는다.

| 대상 | 전략 | 보호하는 규칙 |
|---|---|---|
| Brand와 연결 Product 일괄 삭제 | 활성 Brand 조회에 `PESSIMISTIC_WRITE` 배타 잠금 | 삭제가 진행되는 동안 Brand를 사용하는 쓰기를 대기시킴 |
| 활성 Brand를 사용하는 쓰기 | Brand 조회에 `PESSIMISTIC_READ` 공유 잠금, 후속 변경까지 같은 트랜잭션 유지 | Product 등록과 삭제의 순서를 정하고 삭제 후 쓰기가 활성 상태를 다시 확인하게 함 |
| 연결된 Product 집합 | `brand_id`, `deleted_at IS NULL` 조건부 일괄 갱신 | 활성 Product의 삭제 상태만 바꾸고 재고·상품 정보와 기삭제 Product를 보존 |
| Brand 이름 수정 | `deleted_at IS NULL` 조건부 UPDATE | 이름 수정과 삭제가 같은 Brand 행에서 순서대로 반영되게 함 |

활성 Brand를 사용하는 쓰기는 Product 등록·수정·재고 설정·단독 삭제, Like 등록, DRAFT 생성, Order 확정이다.
해당 요청은 Brand 잠금을 먼저 얻고 활성 상태를 확인한 뒤 후속 저장까지 같은 트랜잭션에서 처리한다.
읽기 전용 조회와 기존 Like 취소는 Brand 잠금을 얻지 않는다.

Brand 행을 잠금 기준으로 삼으면 경합 결과는 다음과 같다.

- **활성 Brand를 사용하는 쓰기가 먼저 잠금을 얻음:** 쓰기가 끝날 때까지 삭제가 기다린다. 쓰기가 Product를 등록했다면 삭제는 등록 commit 후 그 Product까지 갱신한다.
- **삭제가 먼저 배타 잠금을 얻음:** 쓰기가 기다린 뒤 활성 Brand와 Product 상태를 다시 확인한다. 삭제된 대상이면 각 API의 기존 오류 계약으로 종료하고 저장하지 않는다.
- **삭제와 읽기 전용 조회가 겹침:** 조회는 잠금 대기 없이 삭제 전 커밋 상태를 볼 수 있다. 삭제 commit 뒤 시작한 조회에는 삭제 상태가 반영된다.

공유 잠금 조회와 후속 변경은 반드시 같은 트랜잭션에 있어야 한다.
Brand 잠금을 생략하는 쓰기 경로가 있으면 위 순서 보장은 성립하지 않는다.
Product 수정·재고 변경·주문 차감은 Product의 `deleted_at IS NULL` 및 업무 조건부 갱신으로 상품별 삭제 상태와 수량도 확인한다.
여러 Brand와 Product는 ID 오름차순으로 처리해 교착 가능성을 줄인다.

`PESSIMISTIC_READ`는 JPA에 요청하는 잠금 모드다. 실제 MySQL에서 공유 잠금 SQL로 실행되는지는 SQL 로그나 DB 통합 테스트로 확인한다.
더 강한 잠금으로 변환되면 정합성은 유지되지만 Brand별 대기가 늘 수 있다.

## 3. 실패 처리와 재시도

| 결과 | 처리 |
|---|---|
| 활성 Brand 잠금 조회 결과 없음 | 변경 없이 기존 없는 Brand 오류 |
| Product 갱신 영향 행 수 0 | 정상; 활성 Product가 없거나 모두 이미 삭제됨 |
| Brand 조건부 갱신 영향 행 수 0 | 없는 Brand 업무 예외를 Facade 밖으로 전달해 Product 변경도 rollback |
| Product 갱신 뒤 Brand 갱신 또는 commit 전 flush 실패 | Brand와 Product 변경 전체 rollback |
| 잠금 시간 초과·교착 등 저장 기술 오류 | 전체 rollback 후 기술 오류 처리; 업무상 없는 대상 오류로 바꾸지 않음 |
| commit 자체의 오류 | 기술 오류 처리; commit 응답을 확인하지 못한 경우 확정 여부가 불명확할 수 있으므로 rollback 완료로 단정하지 않음 |
| 관리자 접근 거절 | 기존 접근 규칙으로 거절하고 DB 변경 없음 |

Brand 행 잠금으로 삭제와 활성 Brand를 사용하는 쓰기의 순서를 정하므로 낙관적 버전 충돌 재시도는 두지 않는다.
잠금 조회 결과 Brand가 없거나 삭제된 상태면 반복해도 현재 요청의 업무 조건은 바뀌지 않으므로 기존 오류로 종료한다.
잠금 시간 초과와 교착은 현재 시도를 rollback하고 기술 오류로 처리하며, 자동 재시도는 하지 않는다. commit 오류도 자동 재시도하지 않는다.

## 4. 검증 기준

- 재고 0 상품을 포함해 연결된 미삭제 Product와 Brand가 함께 논리 삭제된다. 다른 Brand, 기삭제 Product, 기존 재고·Like·주문 정보는 보존한다.
- Product UPDATE 뒤 Brand 갱신 경계에서 예외를 주입한다. Product 변경이 rollback되고 Brand가 작업 전 상태인지 확인한다.
- Product와 Brand UPDATE를 모두 실행한 뒤 commit 전에 예외를 주입한다. 트랜잭션 종료 후 새 DB 경계에서 두 대상의 삭제 상태와 시각이 모두 작업 전 상태인지 확인한다.
- 삭제가 먼저 잠금을 얻는 경우와 Product 등록이 먼저 잠금을 얻는 경우를 각각 실행해 위 경합 결과를 확인한다.
- 초기 데이터는 worker 시작 전에 commit하고, 요청별 트랜잭션 종료 뒤 새 조회 경계에서 결과를 확인한다.

JPA bulk UPDATE는 영속성 컨텍스트의 관리 객체를 자동 갱신하지 않는다.
같은 트랜잭션에서 이전 Product 객체를 재사용하지 않고, 검증은 트랜잭션 종료 후 새 조회 경계에서 수행한다.
