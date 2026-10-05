# 브랜드 일괄 삭제와 비노출 — 트랜잭션 설계

대상은 `DELETE /api-admin/v1/brands/{brandId}`의 브랜드와 연결된 상품 일괄 논리 삭제다.

## 1. 트랜잭션 범위

| 구분 | 설계                                                                           |
|---|------------------------------------------------------------------------------|
| 경계 | `BrandFacade.delete()` 한 번의 실행을 한 DB 트랜잭션으로 처리                               |
| 함께 변경 | Brand와 연결된 미삭제 Product 전체의 `deletedAt`, `updatedAt`<br/>재고 0인 상품 포함, 연결 상품이 없으면 Brand만 삭제 |
| 보존 | 기삭제 Product의 삭제 시각, 다른 Brand, Product, 재고, 기존 Like, 주문 정보, 포인트 결제 결과         |
| 성공 | 전체 변경을 commit한 뒤 `200 OK` 반환                                                 |
| 실패 | 해당 시도의 변경 전체 rollback. 다른 요청의 commit은 보존                                     |

## 2. 호출 경로와 처리 순서

```text
관리자 요청 → Security FilterChain → BrandV1Controller.delete()
  → 주입된 BrandFacade 프록시: 트랜잭션 시작
    → BrandFacade.delete()
      1. 이번 삭제에 사용할 시각을 한 번 결정
      2. ProductRepository: 해당 Brand의 미삭제 Product 전체를 조건부 갱신
         영향 행 수 0도 정상 처리
      3. BrandRepository: 미삭제 Brand를 조건부 갱신
         영향 행 수 1 → 성공, 0 → NOT_FOUND 예외로 앞의 상품 변경도 rollback
  ← Facade 프록시: commit, 실패 시 rollback 후 예외 전파
← Controller 성공 응답 / ApiControllerAdvice 최종 오류 응답
```

Repository 호출은 **구현체 → Spring Data JPA Repository 프록시 → DB**로 이어진다. Brand와 Product는 별도 애그리게이트이며 Facade가 두 조건부 갱신을 조합한다. 두 SQL은 같은 DB와 트랜잭션 관리자를 사용하고 하나의 물리 트랜잭션에 참여한다.

전파는 `REQUIRED`, 격리 설정은 `DEFAULT`다. Controller가 활성 트랜잭션 없이 별도 Spring bean인 Facade의 public 메서드를 프록시를 통해 호출한다. 응답은 프록시의 commit이 끝난 뒤 반환한다.

## 3. 예외 처리

| 결과 | 처리 |
|---|---|
| Brand UPDATE의 영향 행 수 0 | 없거나 이미 삭제된 Brand. `CoreException(NOT_FOUND)`로 앞의 Product 변경도 rollback한 뒤 `404` |
| Product UPDATE 0 이상, Brand UPDATE 1 | 정상 처리. 전체 commit 후 `200` |
| SQL 실행 또는 commit 과정의 기술 오류 | 이번 요청의 변경 전체 rollback, 기존 `500` 오류 처리 |

RuntimeException, Error는 Facade 밖으로 전파한다. 예외를 삼켜 일부 성공으로 반환하거나 상품별 `REQUIRES_NEW`로 독립 commit하지 않는다. 자기 호출로 트랜잭션 경계를 만들지 않는다.

삭제는 자동 재시도하지 않는다. 교착과 잠금 시간 초과를 없는 대상으로 바꾸지 않는다.

## 4. 동시성 보호

**조건부 갱신을 선택한다.** 논리 삭제는 현재 미삭제 여부를 검사하고 삭제 상태를 기록하는 작업이다. 이름, 가격과 재고를 읽어 다시 저장할 필요가 없으므로, 삭제에 필요한 컬럼만 갱신해 다른 요청이 확정한 값을 보존한다.

| 보호할 행 | 전략과 이유 |
|---|---|
| 대상 Product | `brand_id`와 `deleted_at IS NULL`을 조건으로 일괄 삭제. 재고 0도 포함하며 기삭제 Product는 갱신하지 않음 |
| Brand | `id`와 `deleted_at IS NULL`을 조건으로 삭제. 이미 삭제된 Brand의 삭제 시각을 덮어쓰지 않음 |

MySQL native UPDATE를 사용하며 실행 순서는 Product → Brand다. 두 SQL에 같은 삭제 시각을 전달하고, Brand 갱신까지 성공한 뒤 전체를 commit한다.

```sql
UPDATE products
SET deleted_at = :deletedAt,
    updated_at = :deletedAt
WHERE brand_id = :brandId
  AND deleted_at IS NULL
ORDER BY id;

UPDATE brands
SET deleted_at = :deletedAt,
    updated_at = :deletedAt
WHERE id = :brandId
  AND deleted_at IS NULL;
```

- **수정 시각:** bulk UPDATE는 `@PreUpdate`를 자동 적용하지 않으므로 삭제 SQL에서 `updated_at`을 직접 갱신한다.
- **다른 변경과의 협력:** 같은 행의 다른 쓰기도 `deleted_at IS NULL` 조건과 기능별 갱신 컬럼을 사용한다. 삭제 후 변경은 거절하고, 조회했던 객체 전체를 다시 저장해 삭제 상태나 관계없는 값을 덮어쓰지 않는다.
- **재고 변경과의 경쟁:** 주문의 재고 차감은 현재 상품 상태와 수량을 조건으로 재고만 갱신한다. 차감이 먼저 commit되면 삭제는 변경된 재고를 보존한다. 삭제가 먼저 commit되면 차감의 `deleted_at IS NULL` 조건이 실패해 해당 주문 확정 전체를 rollback한다.
- **DB 잠금과 격리:** InnoDB가 UPDATE에 필요한 배타 잠금을 획득한다. 같은 행의 쓰기는 대기하며 갱신 대상의 잠금은 트랜잭션 종료까지 유지된다. 이 조건부 갱신을 위해 격리 수준을 별도로 높이지 않는다. 서로 다른 브랜드를 공통 JVM 잠금으로 직렬화하지 않는다.
- **상품 갱신 순서:** `ORDER BY id`로 상품 행의 갱신 순서를 주문 차감과 같은 ID 오름차순으로 맞춘다. 실제 잠금 범위와 획득 순서는 인덱스와 실행 계획도 영향을 주므로 `brand_id` 인덱스와 실행 SQL을 확인한다. 교착이 모두 없어지는 것은 아니다.
- **영속성 컨텍스트:** 삭제 경로는 Brand와 Product를 조회해 변경한 뒤 엔티티로 다시 저장하지 않는다. bulk UPDATE 결과는 이미 관리 중인 객체에 자동 반영되지 않으므로 같은 경계에서 오래된 객체를 재사용하지 않는다. `open-in-view: false`를 유지한다.

### 삭제와 겹치는 요청

- **DRAFT 생성과 좋아요 등록:** 삭제 commit 전에 미삭제 Product를 확인한 요청은 성공을 허용하며, 삭제와 직렬화하지 않는다. 주문 확정은 현재 상품의 미삭제 상태를 조건으로 검사한다.

## 5. 핵심 검증 조건

- **성공과 보존:** Brand와 미삭제 Product 전체가 함께 삭제되고 보존 대상은 유지된다. 재고가 0이거나 연결 상품이 없는 경우도 포함한다.
- **중간 실패:** 미삭제 Product가 있는 Brand를 준비하고 Product UPDATE 후 Brand 갱신 경계에서, 또는 두 UPDATE 실행 후 commit 전에 테스트 구성으로 예외를 발생시킨다. 서비스 트랜잭션 종료 후 새 DB 조회에서 삭제 상태와 수정 시각을 포함해 이번 요청의 변경이 모두 취소됐는지 확인한다.
- **동일 Brand 삭제:** 같은 Brand의 삭제 요청 두 개가 겹치고 한 요청이 commit하면, 성공은 `200` 한 건이고 다른 요청은 Brand UPDATE의 영향 행 수 0으로 `404`다. 삭제 시각을 다시 갱신하지 않는다.
- **다른 변경과의 경쟁:** 이름 수정이나 재고 변경이 먼저 commit되면 삭제는 해당 값을 보존한다. 삭제가 먼저 commit되면 수정과 재고 설정은 미삭제 조건을 만족하지 못해 `404`, 주문 차감은 `409`로 거절된다. 삭제 전에 조회한 객체가 있어도 삭제 상태와 최신 재고를 덮어쓰지 않는다.
- **겹친 DRAFT와 좋아요:** 삭제 commit 전에 미삭제 상태를 확인한 요청의 생성과 등록을 허용한다. 이후 DRAFT 확정은 상품의 미삭제 조건을 만족하지 못해 전체 rollback하며, 차감과 확정 결과를 남기지 않는다.
