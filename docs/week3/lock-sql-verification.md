# 잠금 SQL 검증 기록

## 목적

잠금 설계가 실제 호출 경로와 SQL에 적용되는지 통합 테스트에서 확인한다. 코드의 `@Lock` 선언만 확인하지 않고 Hibernate가 생성한 SQL의 잠금 절과 `@Version` 조건을 확인했다.

## 실행 조건

테스트 설정 파일은 수정하지 않고 실행 환경에만 SQL 로그를 주입했다.

```bash
SPRING_APPLICATION_JSON='{"spring":{"jpa":{"show-sql":true}},"logging":{"level":{"org.hibernate.SQL":"DEBUG","org.hibernate.orm.jdbc.bind":"TRACE"}}}' \
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.product.application.ProductUseCaseIntegrationTest' \
  --tests 'com.loopers.like.application.LikeUseCaseIntegrationTest' \
  --tests 'com.loopers.brand.application.BrandUseCaseIntegrationTest' \
  --info --console=plain

SPRING_APPLICATION_JSON='{"spring":{"jpa":{"show-sql":true}},"logging":{"level":{"org.hibernate.SQL":"DEBUG","org.hibernate.orm.jdbc.bind":"TRACE"}}}' \
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.order.application.OrderConcurrencyTest' \
  --info --console=plain
```

두 실행 모두 통과했다. 로그는 테스트 결과의 `system-out`과 실행 중 수집한 SQL 출력에서 확인했다.

## 관찰한 근거


| 경로                   | 관찰한 SQL 형태                                             | 의미                   |
| -------------------- | ------------------------------------------------------ | -------------------- |
| 주문 확정 Product 조회     | `select ... from product ... for share`                | Product 공유락          |
| 좋아요 등록 Product 조회    | `select ... from product ... for share`                | 등록 중 상품 삭제와 순서 조정    |
| 관리자 재고 변경 Product 조회 | `select ... from product ... for share`                | 삭제된 상품의 재고 변경 방지     |
| 주문 확정 Stock 조회       | `select ... from stock ... for update`                 | 재고 차감 경합 직렬화         |
| 관리자 재고 변경 Stock 조회   | `select ... from stock ... for update`                 | 주문과 관리자 수량 변경 경합 직렬화 |
| 브랜드 삭제 Brand 조회      | `select ... from brand ... for update`                 | 브랜드 삭제 시작 시 배타락      |
| 브랜드 삭제 연결 상품 조회      | `select ... from product ... order by p.id for update` | 연결 상품을 ID 순서로 배타락    |
| 상품 삭제 Product 조회     | `select ... from product ... for update`               | 삭제 중 주문·수정·재고 변경 대기  |
| 상품 수정 Product 조회     | `select ... from product ... for update`               | 수정 중 주문 확정·삭제 대기      |
| Point 저장             | `update point ... version=? where id=? and version=?`  | 낙관적 충돌 검사            |
| Order 저장             | `update orders ... version=? where id=? and version=?` | 중복 확정 충돌 검사          |


## 코드 경로 대조

- 주문 확정: `OrderUseCase.confirm` → `ProductRepository.findForOrder` → `StockRepository.findForOrderByProductId` → `PointRepository.findForOrderByUserId`
- 관리자 재고 변경: `ProductUseCase.changeStock` → `ProductRepository.findForStock` → `StockRepository.findForStockUpdateByProductId`
- 브랜드 수정: `BrandUseCase.update` → `BrandRepository.findForWrite` → `BrandRepository.save` (배타락)
- 브랜드 삭제: `BrandUseCase.delete` → `BrandRepository.findForWrite` → `ProductRepository.findAllForBrandDelete`
- 상품 수정·삭제: `ProductUseCase.update/delete` → `ProductRepository.findForWrite`
- 좋아요 등록: `LikeUseCase.register` → `ProductRepository.findForLike`

같은 행을 수정하는 경로가 위 잠금 조회를 우회하지 않는지 repository 호출도 함께 확인했다.
