# R03 구현 계획

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r03-jdbc-and-like-aggregation` · PR 대상: `volume-3/main`

상태: 좋아요 등록·취소(커밋 1~6, 13)와 조회 전환(커밋 7~12) 구현·검증 완료. 커밋 1~12는 Sonnet 위임, 13은 직접 수정. 좋아요 집계(커밋 14~20)도 구현·검증 완료(Sonnet 위임). R03 전체 구현 완료, 결과는 [result.md](result.md).
브랜치는 `volume-3/refacto`에서 분기했고, refacto가 `volume-3/main`에 병합(PR #15)된 뒤 main 위로 리베이스했다.

## 문서와 진행 원칙

각 구현 커밋은 **실패 테스트 확인 → 구현 → 리팩터링 → 관련 테스트 통과 → 커밋** 순서로 진행한다. 테스트와 해당 구현을 함께 커밋한다.
구현은 이 문서를 기준으로 Sonnet 에이전트에 위임하고, 결과 diff·커밋·테스트는 직접 확인한 뒤 체크박스를 갱신한다.
테스트는 가볍게 유지한다. 도메인·Service는 단위 테스트로, 저장은 공유 `@IntegrationTest` 통합 테스트 1개로 확인하고 동시성 테스트는 추가하지 않는다.

## 좋아요 등록·취소

### 설계

결정 근거는 [도메인 구조](trade_off/01-like-aggregate.md), [중복 처리](trade_off/02-like-duplicate.md), [상품 확인](trade_off/03-like-product-check.md)을 따른다.

| 계층 | 요소 | 책임 |
|---|---|---|
| interfaces | `LikeController` | id 형식 검증 후 `RegisterLikeUseCase`·`CancelLikeUseCase` 호출. HTTP 계약(경로·200·404) 유지 |
| application | `RegisterLikeUseCase`, `CancelLikeUseCase`, `LikeService` | `@Transactional`. 등록은 상품 조회·활성 확인 후 저장, 취소는 상품 확인 없이 삭제 |
| domain | `shopping.model.Like`, `shopping.repository.LikeRepository` | Like는 `id`·`userId`·`productId`·`likedAt`, `create(userId, productId)`(id·likedAt null), `restore(...)`. 양수 id가 아니면 `IllegalArgumentException`. Repository는 `void save(Like)`(중복이면 무시), `void delete(long userId, long productId)` |
| infrastructure | `persistence.shopping.jpa.LikeJpaRepository`, `persistence.shopping.repository.LikeRepositoryImpl` | HQL `insert … on conflict do nothing`, JPQL delete. `created_at`은 구현에서 `Instant.now()` |

호출과 트랜잭션 경계는 다음과 같다.

```
POST   /api/v1/products/{productId}/likes
  LikeController.register → LikeService.register (REQUIRED)
    → ProductRepository.findById         없으면 ApplicationException(PRODUCT_NOT_FOUND) → 404
    → Product.ensureActive                삭제면 DomainException(DELETED_PRODUCT)   → 404 "Not Found" (응답은 PRODUCT_NOT_FOUND와 같음)
    → LikeRepository.save(Like.create)    insert … on conflict do nothing (0 또는 1행)

DELETE /api/v1/products/{productId}/likes
  LikeController.cancel → LikeService.cancel (REQUIRED)
    → LikeRepository.delete               delete … where userId and productId (0 또는 1행)
```

- 상품 조회와 저장은 같은 트랜잭션이며 잠금은 없다. 조회와 저장 사이에 상품이 삭제되는 경쟁은 허용한다.
- `X-USER-ID` 사용자 존재 확인은 기존 `@XUserId` resolver가 맡는다. Service는 다시 확인하지 않는다.
- 제거 대상은 `application/shopping/dao/LikeCommandDao`, `infrastructure/dao/shopping/JdbcLikeCommandDao`, `JdbcLikeCommandDaoIntegrationTest`다. 집계 DAO(`LikeCountAggregationDao`, `JdbcLikeCountAggregationDao`)와 조회 DAO는 이 절에서 건드리지 않는다.

### 커밋 1 — 좋아요 도메인 모델과 저장소 계약

- [x] `LikeTest`(POJO)를 먼저 쓴다. create가 id·likedAt을 비우는지, restore가 모든 값을 채우는지, userId·productId가 양수가 아니면 `IllegalArgumentException`인지 확인한다. restore는 id·likedAt 누락도 거절한다.
- [x] `domain/shopping/model/Like.java`, `domain/shopping/repository/LikeRepository.java`를 추가한다. 참고: `domain/mall/model/Brand.java`의 create·restore와 구조 검증 스타일.
- [x] 커밋: `refactor: 좋아요 도메인 모델과 저장소 계약 추가`

### 커밋 2 — 좋아요 저장소 JPA 구현

- [x] `infrastructure/persistence/shopping/repository/LikeRepositoryIntegrationTest`(`@IntegrationTest`)를 먼저 쓴다. 새 등록 1행, 같은 등록을 반복해도 예외 없이 1행이고 `created_at`도 바뀌지 않음, 취소하면 0행, 없는 좋아요를 취소해도 예외 없음을 확인한다. 검증 쿼리는 기존 `JdbcLikeCommandDaoIntegrationTest.countLikes`처럼 JdbcClient를 쓴다.
- [x] `LikeJpaRepository`(`@Modifying @Query`의 HQL insert·JPQL delete)와 `LikeRepositoryImpl`을 추가한다.
- [x] **중단 조건:** HQL on conflict가 MySQL에서 실행되지 않거나, 중복이 예외를 내거나, 기존 행을 바꾸면 구현을 멈추고 실행 SQL 로그와 함께 보고한다. native 쿼리로 대체하지 않는다.
- [x] 테스트 로그에서 HQL insert가 바뀐 실제 MySQL SQL을 확인해 아래 검증 기록에 남긴다.
- [x] 커밋: `refactor: 좋아요 저장소 JPA 구현 추가`

### 커밋 3 — 좋아요 등록·취소 유스케이스

- [x] `LikeServiceTest`(Mockito)를 먼저 쓴다. 상품이 없으면 `PRODUCT_NOT_FOUND`이고 save 미호출, 삭제된 상품이면 `DELETED_PRODUCT`이고 save 미호출, 활성 상품이면 해당 userId·productId로 save 호출, 취소는 delete를 호출하고 상품을 조회하지 않음을 확인한다. 참고: `OrderServiceTest`.
- [x] `application/shopping/usecase/RegisterLikeUseCase`, `CancelLikeUseCase`, `application/shopping/service/LikeService`를 추가한다. 여러 UseCase를 한 Service가 구현하는 방식은 `BrandService`, 상품 확인은 `OrderService`의 `findById` + `ensureActive`를 따른다.
- [x] 커밋: `refactor: 좋아요 등록·취소 유스케이스 추가`

### 커밋 4 — 좋아요 API 전환과 JDBC 좋아요 DAO 제거

- [x] `LikeController`가 UseCase를 호출하도록 바꾼다.
- [x] `LikeCommandDao`, `JdbcLikeCommandDao`, `JdbcLikeCommandDaoIntegrationTest`를 삭제한다. 활성 상품 확인 테스트는 커밋 3의 Service 단위 테스트가 대신한다.
- [x] `LikeApiE2ETest`가 기대값 변경 없이 통과하는지 확인한다. 삭제된 상품의 응답 본문 에러 코드 문자열이 바뀌면 기록하고 보고한다.
- [x] 커밋: `refactor: 좋아요 API를 유스케이스로 전환하고 JDBC 좋아요 DAO 제거`

### 커밋 5 — 레이어별 테스트 문서 갱신

- [x] `docs/test/{domain,application,infrastructure,interfaces}.md`에서 `JdbcLikeCommandDao` 관련 언급, 테스트 목록, 집계 수를 실제에 맞게 갱신한다.
- [x] 커밋: `docs: 좋아요 전환에 맞춰 레이어별 테스트 문서 갱신`

### 검증

- [x] `./gradlew :apps:commerce-api:test --tests "*Like*"`
- [x] `./gradlew :apps:commerce-api:check`를 실행하고 실행 건수·실패·skip·종료 결과를 기록한다.
- [x] 운영 코드에서 `JdbcClient` 사용처가 조회 DAO 6개와 집계 DAO만 남았는지 grep으로 확인한다.
- [x] 기록: HQL insert의 실제 SQL, check 결과.

검증 기록(커밋 1~5 시점)
- HQL insert의 실제 SQL: `insert into product_likes(user_id,product_id,created_at) values (?,?,?) as excluded(user_id,product_id,created_at) on duplicate key update user_id=product_likes.user_id`. 중복이면 값이 바뀌지 않는 갱신이다.
- 취소 SQL: `delete lje1_0 from product_likes lje1_0 where lje1_0.user_id=? and lje1_0.product_id=?`.
- `./gradlew :apps:commerce-api:check` BUILD SUCCESSFUL. test 219건, slowTest 17건, 실패·오류·skip 0건. Checkstyle·ArchUnit 통과.
- 운영 코드의 JdbcClient: 조회 DAO 6개(`JdbcBrandQueryDao`, `JdbcProductLikeCountQueryDao`, `JdbcOrderQueryDao`, `JdbcWalletQueryDao`, `JdbcLikeQueryDao`, `JdbcUserQueryDao`)와 `JdbcLikeCountAggregationDao`만 남음.
- 계획과 다른 점: `LikeRepositoryIntegrationTest`에 클래스 단위 `@Transactional`을 붙였다. `@Modifying` 쿼리는 트랜잭션 밖에서 `TransactionRequiredException`이 나기 때문이며, `BrandRepositoryIntegrationTest`와 같은 방식이다. UseCase는 `register`·`cancel` 메서드로 구현됐고, 삭제된 상품의 응답 코드가 `DELETED_PRODUCT`로 바뀌었다. 뒤의 두 가지는 커밋 6에서 조정한다.

### 커밋 6 — 좋아요 UseCase 형식과 삭제 상품 응답 코드 조정

결정: [UseCase 형식](trade_off/01-like-aggregate.md#구현-후-조정--usecase-형식), [PRODUCT_NOT_FOUND로 되돌리기](trade_off/03-like-product-check.md#구현-후-조정--product_not_found로-되돌리기).

- [x] `LikeServiceTest`를 먼저 고친다. 삭제된 상품이면 `ApplicationException(PRODUCT_NOT_FOUND)`이고 save를 호출하지 않는지 확인한다. 호출은 `execute(LikeCommand.Register)`·`execute(LikeCommand.Cancel)`로 바꾼다.
- [x] `application/shopping/command/LikeCommand`(`Register(long userId, long productId)`, `Cancel(long userId, long productId)` record)를 추가한다. 참고: `application/mall/command/BrandCommand`.
- [x] `RegisterLikeUseCase`·`CancelLikeUseCase`를 `execute(LikeCommand.*)`로 바꾸고, `LikeService`와 `LikeController`를 맞춘다.
- [x] `LikeService.register`는 `productRepository.findById(productId).filter(product -> !product.isDeleted())`가 비면 `ApplicationException(PRODUCT_NOT_FOUND)`를 던진다. `ensureActive`는 호출하지 않는다.
- [x] `LikeApiE2ETest`의 삭제된 상품 케이스에 응답 본문 에러 코드가 `PRODUCT_NOT_FOUND`인지 확인하는 단언 하나를 추가한다. 회귀를 막기 위한 것이며, 새 테스트 메서드는 만들지 않는다. 기존 E2E의 코드 단언 방식을 참고한다.
- [x] 커밋: `refactor: 좋아요 유스케이스를 커맨드 형식으로 맞추고 삭제 상품 응답 코드를 유지`
- 실제: E2E에 추가한 단언은 `errorCode == "Not Found"`다. `ApiErrorMapper`가 두 코드를 모두 `ErrorType.NOT_FOUND`로 바꾸므로 응답 코드는 원래 바뀐 적이 없었다. 삭제 확인 방식은 커밋 13에서 되돌린다.

### 커밋 13 — 삭제 상품 확인을 ensureActive로 되돌림

결정: [PRODUCT_NOT_FOUND로 되돌렸다가 철회](trade_off/03-like-product-check.md#구현-후-조정--product_not_found로-되돌렸다가-철회).

- [x] `LikeServiceTest`의 삭제된 상품 케이스를 `DomainException(DELETED_PRODUCT)` 기대로 먼저 바꾸고 실패를 확인했다.
- [x] `LikeService.register`를 `findById` → `PRODUCT_NOT_FOUND`, `product.ensureActive()`로 되돌렸다. E2E의 `"Not Found"` 단언은 그대로 통과한다.
- [x] 커밋: `refactor: 좋아요 등록의 삭제 상품 확인을 ensureActive로 되돌림` (`872510a`)

## 조회 DAO의 QueryDSL 전환

### 설계

결정 근거는 [조회 전환 방식](trade_off/04-query-conversion.md)을 따른다.

- 조회는 `JPAQueryFactory`와 `Projections.constructor`로 만들고, 엔티티를 조회하지 않는다. Q타입은 기존 JPA 엔티티의 Q클래스(`QBrandJpaEntity`, `QOrderJpaEntity`, `QOrderItemJpaEntity`, `QOrderRecordJpaEntity`, `QLikeJpaEntity`, `QProductJpaEntity`, `QProductLikeCountJpaEntity`, `QWalletJpaEntity`, `QUserJpaEntity`)를 쓴다. 참고: `infrastructure/query/mall/QueryDslProductQueryDao`.
- 필드가 1:1이면 View를 바로 만든다. 중첩 View·변환·여러 View가 필요할 때만 infra Row를 둔다(`OrderHeaderRow`, `OrderItemRow` 유지. Like는 `BrandSummaryView` 중첩 때문에 Row 또는 중첩 Projection 중 단순한 쪽).
- `application` 계약(QueryDao 인터페이스, View)과 공개 메서드 시그니처는 바꾸지 않는다. 각 DAO의 `@Transactional(readOnly = true)`는 유지한다.
- SQL 의미는 그대로 옮긴다. 조건(`deleted = false` 등), 정렬(`created_at DESC, id DESC` 등), `LIMIT/OFFSET`, `COUNT`, `COALESCE(like_count, 0)`, LEFT/INNER JOIN 구분을 유지한다. 매핑된 연관관계가 있으면 연관 경로로, 없으면 `on`의 id 비교로 조인한다.
- 주문 목록은 헤더 페이지 조회 → 품목 `IN` 조회의 2단계를 유지한다. fetch join과 엔티티 조회는 쓰지 않는다.
- 클래스 이름은 `Jdbc*QueryDao` → `QueryDsl*QueryDao`, 테스트 클래스도 같은 규칙으로 바꾼다. 테스트 기대값은 바꾸지 않는다.

### 커밋 7 — 상품 쓰기 응답을 조회 경로로 만들고 좋아요 수 조회 DAO 제거

- [x] `CreateProductUseCase`·`UpdateProductUseCase`·`SetProductStockUseCase`가 상품 id(`long`)를 반환하도록 바꾼다. `ProductService`에서 `ProductLikeCountQueryDao` 의존과 `result(...)`를 제거한다. 브랜드 활성 확인(`findBrand` → `brand.ensureActive()`)은 업무 규칙이므로 유지한다.
- [x] `AdminProductController`의 생성·수정·재고 설정이 UseCase의 id로 `productQueryDao.findAdminProduct(id)`를 호출해 응답을 만든다. 비어 있으면 기존 `notFound()`를 쓴다. 생성은 기존처럼 201이다.
- [x] 쓰이지 않게 된 `ProductResult`, `AdminProductView.from(ProductResult)`, `application/mall/query/ProductLikeCountQueryDao`, `infrastructure/query/mall/JdbcProductLikeCountQueryDao`를 삭제한다.
- [x] 관련 단위 테스트(`ProductServiceTest` 등이 있으면)를 반환값 변경에 맞춘다. 관리자 상품 E2E 기대값은 바꾸지 않고 통과해야 한다.
- [x] 커밋: `refactor: 상품 쓰기 응답을 조회 경로로 만들고 좋아요 수 조회 DAO 제거`

### 커밋 8 — mall 조회 DAO 전환

- [x] `JdbcBrandQueryDao` → `QueryDslBrandQueryDao`(단건, 페이지, `COUNT`). `BrandApiE2ETest`·관리자 브랜드 E2E가 기대값 변경 없이 통과한다.
- [x] 커밋: `refactor: 브랜드 조회 DAO를 QueryDSL로 전환`

### 커밋 9 — pay 조회 DAO 전환

- [x] `JdbcWalletQueryDao` → `QueryDslWalletQueryDao`. `JdbcWalletQueryDaoIntegrationTest` → `QueryDslWalletQueryDaoIntegrationTest`(이름만 변경).
- [x] 커밋: `refactor: 지갑 조회 DAO를 QueryDSL로 전환`

### 커밋 10 — shopping 조회 DAO 전환

- [x] `JdbcUserQueryDao` → `QueryDslUserQueryDao`. `@XUserId` resolver가 모든 요청에서 호출하므로 조회는 id 한 컬럼만 가져온다.
- [x] `JdbcLikeQueryDao` → `QueryDslLikeQueryDao`(`product_likes` ⋈ `products` ⋈ `brands` ⟕ `product_like_counts`, 정렬 `created_at DESC, product_id DESC`, `COUNT`).
- [x] 두 통합 테스트는 이름만 변경한다.
- [x] 커밋: `refactor: 사용자·좋아요 조회 DAO를 QueryDSL로 전환`

### 커밋 11 — ordering 조회 DAO 전환

- [x] `JdbcOrderQueryDao` → `QueryDslOrderQueryDao`. 사용자별·관리자 목록(헤더 페이지 + 품목 `IN`), 단건(헤더 + 품목), `COUNT`를 옮긴다. 주문 기록은 LEFT JOIN으로 유지한다.
- [x] `JdbcOrderQueryDaoIntegrationTest` → `QueryDslOrderQueryDaoIntegrationTest`(이름만 변경).
- [x] 커밋: `refactor: 주문 조회 DAO를 QueryDSL로 전환`

### 커밋 12 — 테스트 문서 갱신

- [x] `docs/test/*.md`에서 바뀐 클래스 이름과 삭제된 DAO를 반영한다.
- [x] 커밋: `docs: 조회 DAO 전환에 맞춰 레이어별 테스트 문서 갱신`

### 검증

- [x] 커밋마다 해당 컨텍스트 테스트(`--tests "*Brand*"` 등)를 실행한다.
- [x] 마지막에 `./gradlew :apps:commerce-api:check`를 실행하고 건수·실패·skip을 기록한다.
- [x] 운영 코드의 JdbcClient가 `JdbcLikeCountAggregationDao` 하나만 남았는지 grep으로 확인한다.
- [x] 주문 목록 1회 조회의 실행 SQL이 COUNT·헤더·품목 3회인지 테스트 로그로 확인해 기록한다.

검증 기록(커밋 6~12 시점, 커밋 13 후 좋아요 테스트·Checkstyle 재확인)
- `./gradlew :apps:commerce-api:check` BUILD SUCCESSFUL. test 219건, slowTest 17건, 실패·오류·skip 0건. 커밋 13 후 `--tests "*Like*"` 34건 통과, Checkstyle 통과.
- 운영 코드의 JdbcClient는 `JdbcLikeCountAggregationDao` 하나만 남음.
- 주문 목록 1회 조회: 헤더 페이지(`orders` ⟕ `order_records`, `order by created_at desc, id desc limit`) → 품목(`order_items where order_id in (...) order by order_id, id`) → `count(o.id)` 3회. JDBC 때와 달리 COUNT가 마지막이며 쿼리 구성은 같다.
- 계획과 다른 점: `Projections.constructor`가 package-private 생성자를 찾지 못해 `OrderHeaderRow`·`OrderItemRow`를 `public record`로 바꿨다(기존 `ProductQueryRow`와 같음). Brand DAO에는 전용 통합 테스트가 없어 이름을 바꿀 테스트가 없었다. Like 조회는 Row 없이 중첩 `Projections.constructor`로 `BrandSummaryView`를 만든다. `ProductService`의 수정·재고 설정에서 `findBrand`가 `save`보다 먼저 실행되지만 같은 트랜잭션이라 결과는 같다.

## 좋아요 집계

### 설계

결정 근거는 [집계 전략](trade_off/05-like-count-strategy.md), [변경 추적](trade_off/07-like-change-tracking.md), [추가·삭제 감지](trade_off/08-like-insert-detection.md), [반영과 전체 재집계](trade_off/09-like-count-flush.md)를 따른다.

| 계층 | 요소 | 책임 |
|---|---|---|
| domain | `LikeRepository` | `boolean save(Like)`(새로 저장됐으면 true), `boolean delete(long userId, long productId)`(실제로 지웠으면 true) |
| infrastructure | `LikeJpaRepository`, `LikeRepositoryImpl` | 등록은 native `INSERT IGNORE`, 취소는 JPQL delete. 영향받은 행 수가 1 이상이면 true |
| application | `LikeService` | true일 때만 `ProductLikeChangedEvent(productId, +1 / −1)` 발행 |
| application | `ProductLikeChangedEvent` | 상품 id와 증감분을 담는 record (`application/shopping/event`) |
| application | `LikeCountDeltaBuffer` | `@TransactionalEventListener(phase = AFTER_COMMIT)`로 이벤트를 받아 `ConcurrentHashMap<Long, Long>`에 `merge`로 누적. `drain()`은 키별 `remove`로 원자적으로 꺼내며 합이 0인 항목은 제외. `restore(Map)`은 다시 `merge` |
| application | `FlushLikeCountDeltaUseCase`, `LikeCountDeltaFlushService` | `drain` → 비어 있으면 종료 → `LikeCountAggregationDao.addDeltas` (`@Transactional`). 실패하면 `restore` 후 예외 전파 |
| application | `LikeCountAggregationDao` | 기존 `resetAllCounts`·`aggregateAllCounts`에 `void addDeltas(Map<Long, Long> deltas)` 추가 |
| infrastructure | `JdbcLikeCountAggregationDao.addDeltas` | JDBC 배치: `INSERT INTO product_like_counts (product_id, like_count) VALUES (?, GREATEST(0, ?)) ON DUPLICATE KEY UPDATE like_count = GREATEST(0, like_count + ?)` |
| infrastructure | `LikeCountAggregationScheduler` | `@Scheduled(fixedDelay = 5_000)`로 delta 반영 호출. 실패는 로그만 남기고 다음 주기 유지. 10초 전체 재집계는 제거 |
| infrastructure | `LikeCountStartupAggregator` (`infrastructure/scheduler/shopping`) | `SmartInitializingSingleton`으로 웹 서버 시작 전에 기존 전체 재집계(`LikeCountAggregationUseCase`)를 1회 실행. 실패는 로그. 스케줄러와 같은 `scheduler.like-count.enabled` 조건 |
| infrastructure | `LikeJpaEntity` | 유니크 키 `uk_product_likes_user_product`의 컬럼 순서를 `(product_id, user_id)`로 변경 |

흐름은 다음과 같다.

```
POST/DELETE 좋아요 → LikeService (REQUIRED)
  → LikeRepository.save / delete  → true면 publish(ProductLikeChangedEvent)
커밋 후 → LikeCountDeltaBuffer.on(event)  → map.merge(productId, delta)
롤백되면 → 이벤트 리스너 실행 안 됨

앱 시작(웹 서버 시작 전) → LikeCountStartupAggregator → 전체 재집계 1회 (기존 SQL)
5초마다 → LikeCountAggregationScheduler → LikeCountDeltaFlushService
  → buffer.drain() → dao.addDeltas (배치 upsert) → 실패 시 buffer.restore()
```

- 트랜잭션 없이 호출된 등록·취소는 없다. `LikeService`의 두 메서드는 모두 `@Transactional`이므로 `AFTER_COMMIT` 리스너가 항상 동작한다.
- 한계(문서화 완료): 비정상 종료 시 최대 5초분 유실은 다음 시작 시 전체 재집계로 보정. 서버 여러 대일 때 재시작 재집계와 다른 서버 delta의 겹침. 정상 종료 flush 없음. 운영 DB 유니크 키 변경은 별도 스키마 절차 필요.

### 커밋 14 — 좋아요 유니크 키 순서 변경

- [x] `LikeJpaEntity`의 `@UniqueConstraint(name = "uk_product_likes_user_product", columnNames = {"product_id", "user_id"})`로 바꾼다. 제약 이름은 유지한다.
- [x] 기존 `LikeStorageIntegrationTest`·좋아요 조회 테스트가 기대값 변경 없이 통과한다.
- [x] 커밋: `refactor: 좋아요 유니크 키를 상품·사용자 순서로 변경`

### 커밋 15 — 좋아요 등록을 INSERT IGNORE로 바꾸고 추가·삭제 여부 반환

- [x] `LikeRepositoryIntegrationTest`를 먼저 고친다. 새 등록은 true, 같은 등록 반복은 false이고 행 1개·`created_at` 유지, 있는 좋아요 취소는 true, 없는 좋아요 취소는 false.
- [x] `LikeRepository`를 `boolean save`·`boolean delete`로 바꾸고, `LikeJpaRepository`의 등록을 native `INSERT IGNORE INTO product_likes (user_id, product_id, created_at) VALUES (:userId, :productId, :createdAt)`로 바꾼다. `LikeRepositoryImpl`은 영향받은 행 수 > 0을 반환한다.
- [x] **중단 조건:** 드라이버 기본 설정에서 중복 `INSERT IGNORE`가 0이 아닌 값을 돌려주면 구현을 멈추고 실제 값과 SQL을 보고한다. 설정이나 다른 방식으로 바꾸지 않는다.
- [x] 커밋: `refactor: 좋아요 등록을 INSERT IGNORE로 바꾸고 추가·삭제 여부를 반환`

### 커밋 16 — 좋아요 변경 이벤트 발행

- [x] `LikeServiceTest`를 먼저 고친다. save가 true면 `(productId, +1)` 발행, false면 미발행. delete가 true면 `(productId, −1)` 발행, false면 미발행. 상품 없음·삭제된 상품이면 저장·발행 모두 없음.
- [x] `application/shopping/event/ProductLikeChangedEvent`를 추가하고, `LikeService`가 `ApplicationEventPublisher`로 발행한다.
- [x] 커밋: `feat: 좋아요가 실제로 바뀌면 상품 좋아요 변경 이벤트를 발행`

### 커밋 17 — 좋아요 증감분 누적기

- [x] `LikeCountDeltaBufferTest`(POJO)를 먼저 쓴다. 같은 상품의 증감 누적, `drain`이 값을 돌려주고 비움, 합이 0인 상품 제외, `restore` 후 새 증감과 합산.
- [x] `LikeCountDeltaBuffer`를 추가한다(`@Component`, `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`).
- [x] 이벤트 연결 통합 테스트 1개(`@IntegrationTest`, 예: `LikeCountDeltaBufferIntegrationTest`)를 추가한다. `RegisterLikeUseCase`로 등록하면 커밋 후 +1, 같은 등록을 반복해도 추가 증감 없음, 취소하면 −1. 싱글톤 버퍼이므로 `@BeforeEach`·`@AfterEach`에서 `drain`으로 비운다. 테스트 전체를 트랜잭션으로 감싸지 않는다(`AFTER_COMMIT`이 동작해야 함).
- [x] 커밋: `feat: 커밋된 좋아요 변경을 상품별 증감분으로 메모리에 누적`

### 커밋 18 — 증감분 반영 유스케이스와 배치 upsert

- [x] `LikeCountDeltaFlushServiceTest`(Mockito)를 먼저 쓴다. 비어 있으면 DAO 미호출, 값이 있으면 그대로 `addDeltas` 호출, DAO가 실패하면 버퍼에 `restore`하고 예외 전파.
- [x] `LikeCountAggregationIntegrationTest`에 `addDeltas` 케이스를 추가한다. 행이 없으면 생성, 기존 값에 더함, 음수 결과는 0.
- [x] `FlushLikeCountDeltaUseCase`, `LikeCountDeltaFlushService`, `LikeCountAggregationDao.addDeltas`와 JDBC 배치 구현을 추가한다(배치는 `JdbcTemplate.batchUpdate` 등).
- [x] 커밋: `feat: 누적된 좋아요 증감분을 배치 upsert로 반영하는 유스케이스 추가`

### 커밋 19 — 스케줄러를 5초 delta 반영과 시작 시 전체 재집계로 전환

- [x] `LikeCountAggregationSchedulerTest`를 고친다. 반영 유스케이스에 위임하고, 실패를 전파하지 않는다.
- [x] `LikeCountStartupAggregatorTest`(POJO)를 추가한다. 초기화 시 전체 재집계를 1회 호출하고, 실패를 전파하지 않는다.
- [x] `LikeCountAggregationScheduler`를 `@Scheduled(fixedDelay = 5_000)` + `FlushLikeCountDeltaUseCase`로 바꾸고, `LikeCountStartupAggregator`를 추가한다. 둘 다 `@ConditionalOnProperty(name = "scheduler.like-count.enabled", havingValue = "true", matchIfMissing = true)`(test 프로필은 false).
- [x] 커밋: `refactor: 좋아요 집계를 5초 증감분 반영과 시작 시 전체 재집계로 전환`

### 커밋 20 — 문서 갱신

- [x] `docs/test/*.md`의 좋아요·집계 테스트 목록과 개수를 갱신한다.
- [x] 커밋: `docs: 좋아요 집계 전환에 맞춰 레이어별 테스트 문서 갱신`

### 검증

- [x] 커밋마다 `--tests "*Like*"`와 Checkstyle을 실행한다.
- [x] 마지막에 `./gradlew :apps:commerce-api:check`를 실행하고 건수·실패·skip을 기록한다.
- [x] 기록: 중복 `INSERT IGNORE`의 반환값, 배치 upsert SQL, 운영 코드의 JdbcClient가 `JdbcLikeCountAggregationDao`에만 있는지.

검증 기록(커밋 14~20 시점)
- 중복 `INSERT IGNORE`: 드라이버 기본 설정에서 새 등록 1, 중복 0을 반환했다(임시 측정 후 삭제, `LikeRepositoryIntegrationTest`가 true/false로 고정).
- 배치 upsert: `JdbcTemplate.batchUpdate`로 `INSERT INTO product_like_counts (product_id, like_count) VALUES (?, GREATEST(0, ?)) ON DUPLICATE KEY UPDATE like_count = GREATEST(0, like_count + ?)` 실행, 인자 `[productId, delta, delta]`.
- `./gradlew :apps:commerce-api:check` BUILD SUCCESSFUL. test 236건, slowTest 17건, 실패·오류·skip 0건. Checkstyle·ArchUnit 통과(ArchUnit이 이벤트·리스너·`SmartInitializingSingleton` 배치를 막지 않음).
- 운영 코드의 JdbcClient는 `JdbcLikeCountAggregationDao` 하나(배치용 `JdbcTemplate`도 함께 사용).
- 계획과 다른 점: `JdbcLikeCountAggregationDao`가 `batchUpdate`를 위해 `JdbcTemplate`도 주입받는다. `LikeCountDeltaBufferIntegrationTest`는 브랜드·상품을 Repository로 준비한다. `docs/test` 개수는 수기 계산이다.
- 확인한 한계: 반영 트랜잭션이 커밋 단계에서 실패하면 이미 꺼낸 증감분은 되돌려지지 않는다(`restore`는 `addDeltas` 예외만 처리). 다음 시작 시 전체 재집계가 보정한다.
- 커밋 메시지 정리: 사용자 요청으로 브랜치의 모든 커밋에서 Co-Authored-By 줄을 제거했다(메시지만 재작성, 커밋별 트리 동일 확인).
