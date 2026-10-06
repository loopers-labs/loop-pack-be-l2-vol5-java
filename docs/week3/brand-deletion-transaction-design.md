# 브랜드 일괄 삭제 — 트랜잭션 설계

대상은 관리자 API `DELETE /api-admin/v1/brands/{brandId}`다. 브랜드와 연결된 미삭제 상품을 논리 삭제한다.

## 1. 트랜잭션 경계와 흐름

`BrandFacade.delete()` 한 번의 실행을 하나의 DB 트랜잭션으로 처리한다. 재고가 0인 상품도 대상이며, 미삭제 상품이 없으면 브랜드만 삭제한다. 기삭제 상품, 다른 브랜드와 상품, 재고, 기존 Like, 주문 정보와 포인트 결제 결과는 보존한다.

```text
관리자 요청 → Security FilterChain → BrandV1Controller.delete()
  → 주입된 BrandFacade 프록시: 트랜잭션 시작
    → BrandFacade.delete(brandId)
      1. 활성 Brand 조회 → BrandDeletionPolicy가 삭제 시각 결정
      2. ProductRepository: 해당 Brand의 미삭제 Product 전체를 조건부 갱신
         재고가 0인 상품도 포함, 영향 행 수 0은 정상
      3. BrandRepository: 미삭제 Brand를 조건부 갱신
         영향 행 수 1 → 정상
         영향 행 수 0 → NOT_FOUND 예외를 던져 Product 갱신도 rollback
  ← Facade 프록시: commit 또는 rollback 후 예외 전파
← Controller 성공 응답 / ApiControllerAdvice 오류 응답
```

Facade가 두 애그리게이트의 조건부 갱신을 조합한다. Repository 호출은 구현체에서 Spring Data JPA Repository 프록시를 거쳐 DB에 도달한다. 두 SQL은 같은 DB와 트랜잭션 관리자를 사용하므로 하나의 물리 트랜잭션에 참여한다. 전파는 `REQUIRED`, 격리 설정은 `DEFAULT`다. Controller 진입 시 활성 트랜잭션은 없으며, 성공 응답은 Facade 프록시의 commit이 끝난 뒤 반환한다.

## 2. 동시성 보호

삭제는 미삭제 상태를 조건으로 조건부 갱신한다. 조회한 이름·가격·재고를 다시 저장하지 않고 삭제 시각만 기록해 최신 정보를 보존한다. 실행 순서는 **Product → Brand**이며 두 UPDATE에 같은 시각을 전달한다.

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

상품 갱신은 재고가 0인 상품도 포함하고 기삭제 상품은 건드리지 않는다. `updated_at`은 bulk UPDATE에서 자동 갱신되지 않으므로 SQL에서 직접 설정한다. 삭제 경로는 조회한 엔티티를 다시 저장하지 않는다. 조건부 UPDATE의 결과가 관리 중인 객체에 반영되지 않으므로 같은 경계에서 오래된 객체를 재사용하지 않는다.

같은 Product를 갱신하는 다른 경로도 `deleted_at IS NULL` 조건과 기능별 갱신 컬럼을 사용한다. 주문 재고 차감은 현재 재고와 삭제 여부를 조건으로 갱신한다. 차감이 먼저 commit되면 삭제는 최신 재고를 보존하고, 삭제가 먼저 commit되면 차감은 실패해 주문 확정 전체가 rollback된다. DRAFT 생성과 좋아요 등록은 삭제 commit 전에 상품을 확인한 요청의 성공을 허용한다. 주문 확정은 상품 사용 가능 여부를 다시 확인한다.

InnoDB는 UPDATE 대상에 배타 잠금을 사용하며 격리 수준을 별도로 높이지 않는다. 여러 상품은 `ORDER BY id`로 처리해 주문 차감과 순서를 맞춘다. 인덱스와 실행 계획이 실제 잠금 범위에 영향을 주므로 DB에서 확인하고, 순서 통일이 모든 교착을 없앤다고 가정하지 않는다.

## 3. 오류 처리

| 결과 | 처리 |
|---|---|
| Brand UPDATE 영향 행 수 0 | 앞선 Product 변경도 rollback하고 `404` |
| Brand UPDATE 영향 행 수 1 | 전체 commit 후 `200` |
| SQL 또는 commit 기술 오류 | 전체 rollback 후 기존 기술 오류 응답 |

RuntimeException과 Error는 Facade 밖으로 전파한다. 예외를 삼켜 부분 성공으로 반환하거나 상품마다 `REQUIRES_NEW`를 사용하지 않는다. 삭제는 자동 재시도하지 않는다. Brand 갱신 행 수가 0이면 대상이 없거나 이미 삭제된 상태라 재시도해도 같은 결과이며, 교착·잠금 시간 초과는 별도 기술 오류로 처리한다.

## 4. 검증 기준

- 관리자 권한이 없거나 요청자 식별값이 없는 요청은 기존 접근 규칙으로 거절되며 DB를 변경하지 않는다.
- Brand와 미삭제 Product 전체가 함께 삭제된다. 재고 0, 상품 없음, 기삭제 상품, 다른 Brand도 확인하고 기존 재고·Like·주문 정보를 보존한다.
- Product UPDATE 뒤 Brand 갱신 단계에서 실패를 주입한다. 트랜잭션 종료 후 새 DB 경계에서 Brand와 Product의 삭제 상태·시각이 모두 이전 값인지 확인한다.
- 같은 Brand 삭제 요청이 겹치면 한 요청만 `200`, 나머지는 `404`이며 삭제 시각을 덮어쓰지 않는다.
- 상품 수정·재고 변경과 삭제의 경합에서 기존 값이 보존되는지 확인한다. 삭제 후 새 수정·재고 변경은 `404`, 삭제 후 주문 차감은 `409`이며 확정 전체가 rollback된다.
- 삭제와 겹친 DRAFT 생성·좋아요 등록은 허용한다. 해당 DRAFT의 이후 확정은 삭제된 상품을 감지해 거절하고 재고·포인트·확정 결과를 남기지 않는다.
