# R03 구현 결과

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [구현 계획](plan.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r03-jdbc-and-like-aggregation` (`volume-3/refacto`에서 분기, refacto 병합(PR #15) 후 `volume-3/main`의 `b81d60e` 위로 리베이스) · PR 대상: `volume-3/main`

상태: 구현·검증 완료. 최종 check에서 test 236건, slowTest 17건 모두 통과했다(실패·오류·skip 0). Checkstyle·ArchUnit도 통과했다.
리베이스 전후 최종 트리가 같음을 확인했다(main의 병합 커밋 트리 = 원격 refacto 트리).

## 1. 구현 결과

R03의 목표 세 가지를 모두 적용했다.
- 조회는 QueryDSL로 옮겼다.
- 쓰기는 JPA로 옮겼다.
- 좋아요 수 집계는 변경분만 반영하도록 바꿨다.

그 결과 운영 코드의 JdbcClient는 배치 집계 DAO(`JdbcLikeCountAggregationDao`) 하나에만 남았다.

| 영역 | 이전 | 이후 | 결정 |
|---|---|---|---|
| 좋아요 쓰기 구조 | `LikeController` → `LikeCommandDao`(JdbcClient) | UseCase(`execute(LikeCommand.*)`) → `LikeService` → 독립 `Like` 애그리거트 + `LikeRepository` | [01](trade_off/01-like-aggregate.md) |
| 좋아요 중복 처리 | `EXISTS` → `INSERT` → 중복 예외 catch 후 재확인 | native `INSERT IGNORE` 1회. `boolean save`로 실제 추가 여부 반환. 취소는 JPQL delete 1회, `boolean delete` | [02](trade_off/02-like-duplicate.md) → [08](trade_off/08-like-insert-detection.md) |
| 등록 시 상품 확인 | Controller가 별도 트랜잭션으로 `existsActiveProduct` 호출 | 같은 트랜잭션에서 `findById` + `ensureActive`, 잠금 없음. 응답은 기존과 같은 404 `"Not Found"` | [03](trade_off/03-like-product-check.md) |
| 조회 DAO | JdbcClient 6개(Brand, ProductLikeCount, Wallet, User, Like, Order) | `QueryDsl*QueryDao` 5개. `Projections.constructor`로만 조회하고 엔티티는 조회하지 않음. 주문 목록은 2단계 조회 유지. ProductLikeCount는 제거 | [04](trade_off/04-query-conversion.md) |
| 상품 쓰기 응답 | `ProductService`(쓰기)가 조회 계약 `ProductLikeCountQueryDao`로 좋아요 수를 붙임 | UseCase는 상품 id만 반환하고, Controller가 `findAdminProduct`로 응답을 만듦. `ProductResult`와 `AdminProductView.from` 제거 | [04](trade_off/04-query-conversion.md) |
| 좋아요 수 집계 | 10초마다 전체 0 초기화 + `INSERT … SELECT` 전체 재집계 | 커밋 후 이벤트로 상품별 증감분을 메모리에 누적하고 5초마다 JDBC 배치 upsert. 전체 재집계는 웹 서버가 뜨기 전 1회만 실행 | [05](trade_off/05-like-count-strategy.md) · [07](trade_off/07-like-change-tracking.md) · [09](trade_off/09-like-count-flush.md) |
| `product_likes` 인덱스 | 유니크 `(user_id, product_id)` | 유니크 `(product_id, user_id)`. 상품별 COUNT에도 사용 | [09](trade_off/09-like-count-flush.md) |
| 테스트 코드 | JdbcClient로 데이터 준비·검증 | 유지(허용으로 결정) | [04 §6번](trade_off/04-query-conversion.md#테스트-코드의-jdbcclient-허용-6번) |

### 호출·SQL·트랜잭션 경계

```
POST /api/v1/products/{id}/likes
  LikeController → RegisterLikeUseCase → LikeService.execute(Register)  @Transactional
    ProductRepository.findById                 없으면 PRODUCT_NOT_FOUND(404)
    Product.ensureActive                       삭제면 DELETED_PRODUCT(404, 응답 errorCode "Not Found")
    LikeRepository.save → INSERT IGNORE INTO product_likes … (새로 1 / 중복 0)
    1이면 publish ProductLikeChangedEvent(productId, +1)
  커밋 후  LikeCountDeltaBuffer.on (@TransactionalEventListener AFTER_COMMIT) → map.merge

DELETE /api/v1/products/{id}/likes
  … LikeService.execute(Cancel)  @Transactional
    LikeRepository.delete → delete from product_likes where user_id=? and product_id=? (1 / 0)
    1이면 publish (productId, −1)

앱 시작(웹 서버 시작 전)  LikeCountStartupAggregator (SmartInitializingSingleton)
    → LikeCountAggregationUseCase: 전체 0 초기화 + INSERT … SELECT GROUP BY (기존 SQL, 1회)
5초마다  LikeCountAggregationScheduler.flush → LikeCountDeltaFlushService  @Transactional
    buffer.drain() → JdbcLikeCountAggregationDao.addDeltas (JdbcTemplate.batchUpdate)
      INSERT INTO product_like_counts … VALUES (?, GREATEST(0, ?))
      ON DUPLICATE KEY UPDATE like_count = GREATEST(0, like_count + ?)
    addDeltas가 실패하면 buffer.restore 후 예외 전파(스케줄러가 로그만 남김)
```

주문 목록 1회 조회의 SQL은 다음과 같다(QueryDSL 전환 후).
1. 헤더 페이지: `orders` ⟕ `order_records`, `order by created_at desc, id desc limit`
2. 품목: `order_items where order_id in (…) order by order_id, id`
3. 개수: `count(o.id)`

모두 3회로, JDBC 때와 구성이 같다. COUNT의 실행 순서만 첫 번째에서 마지막으로 바뀌었다.

## 2. 실행·검증 결과

| 시점 | 명령 | 결과 |
|---|---|---|
| 좋아요 등록·취소(커밋 1~5) | `./gradlew :apps:commerce-api:check` | test 219, slowTest 17, 실패·skip 0 |
| 조회 전환(커밋 6~12) | 같음 | test 219, slowTest 17, 실패·skip 0 |
| `ensureActive` 되돌림(커밋 13) | 같음 | test 219, slowTest 17, 실패·skip 0 |
| 좋아요 집계(커밋 14~20), 최종 | 같음 | **test 236, slowTest 17, 실패·오류·skip 0**, Checkstyle·ArchUnit 통과 |

### 핵심 증거

- **HQL on conflict(초기 등록 방식):** MySQL에서 `insert … as excluded(…) on duplicate key update user_id=product_likes.user_id`로 실행됐다. 중복이어도 행 1개가 유지되고 `created_at`도 바뀌지 않았다. 이후 집계 결정으로 `INSERT IGNORE`로 대체했다.
- **`INSERT IGNORE` 반환값:** 드라이버 기본 설정(`useAffectedRows=false`)에서 새 등록은 1, 중복은 0이었다. `LikeRepositoryIntegrationTest`가 true/false로 이 결과를 확인한다.
- **`AFTER_COMMIT` 연결:** `LikeCountDeltaBufferIntegrationTest`로 확인했다. 등록 후 +1, 같은 등록을 반복해도 추가 증감이 없고, 취소하면 −1이다.
- **배치 upsert:** `LikeCountAggregationIntegrationTest`로 확인했다. 행이 없으면 만들고, 기존 값에 더하며, 결과가 음수면 0으로 막는다.
- **응답 계약:** 관리자 상품, 브랜드, 주문, 지갑, 좋아요 E2E를 기대값 변경 없이 통과했다. 삭제된 상품에 좋아요를 누르는 경우 E2E에 `errorCode == "Not Found"` 단언을 추가했다.
- **JdbcClient 잔존:** `grep -rln JdbcClient apps/commerce-api/src/main`의 결과는 `JdbcLikeCountAggregationDao.java` 하나다.

## 3. 계획과의 차이

| 항목 | 계획 | 실제 | 이유 |
|---|---|---|---|
| 좋아요 UseCase 형식 | `register`·`cancel` 메서드 | `execute(LikeCommand.Register/Cancel)` (커밋 6) | 다른 UseCase의 관례에 맞춤([01](trade_off/01-like-aggregate.md#구현-후-조정--usecase-형식)) |
| 삭제 상품 확인 | `ensureActive` | `PRODUCT_NOT_FOUND`로 되돌렸다가(커밋 6) 다시 `ensureActive`(커밋 13) | "응답 코드가 바뀐다"는 보고를 확인하지 않은 채 전달했으나 틀린 전제였다([03](trade_off/03-like-product-check.md#구현-후-조정--product_not_found로-되돌렸다가-철회)) |
| 등록 SQL | HQL on conflict | native `INSERT IGNORE` (커밋 15) | delta 집계에 실제 추가 여부가 필요했다([08](trade_off/08-like-insert-detection.md)) |
| `LikeRepository` 반환 | `void save`·`void delete` | `boolean save`·`boolean delete` | 같음. [01](trade_off/01-like-aggregate.md)의 남은 사항에서 예상한 변화 |
| 조회 전환 대상 | 6개 | 5개 + ProductLikeCount 제거 | 상품 쓰기 응답을 조회 경로로 옮기며 불필요해짐([04](trade_off/04-query-conversion.md)) |
| Order Row 가시성 | 유지 | `OrderHeaderRow`·`OrderItemRow`를 `public record`로 | `Projections.constructor`가 package-private 생성자를 찾지 못함. `ProductQueryRow`와 같은 처리 |
| Like 조회 Row | Row 또는 중첩 Projection | 중첩 `Projections.constructor` | 더 단순한 쪽 |
| 집계 DAO | JdbcClient | JdbcClient + `JdbcTemplate`(배치) | `batchUpdate`용 |
| `LikeRepositoryIntegrationTest` | 트랜잭션 없음 | 클래스 단위 `@Transactional` | `@Modifying` 쿼리는 트랜잭션 밖에서 `TransactionRequiredException`. `BrandRepositoryIntegrationTest`와 같은 방식 |
| 커밋 메시지 | Co-Authored-By 줄 포함 | 사용자 요청으로 브랜치 전체에서 제거 | 메시지만 재작성했고 커밋별 트리가 같음을 확인한 뒤 백업 브랜치는 사용자 요청으로 삭제 |

## 4. 한계와 후속 검토

- **비정상 종료 시 증감분 유실:** 최대 5초분을 잃을 수 있다. 다음 시작 시 전체 재집계가 보정한다. 정상 종료 flush는 사용자 결정으로 두지 않았다.
- **커밋 단계 실패:** 반영 트랜잭션이 커밋 단계에서 실패하면 이미 꺼낸 증감분이 되돌려지지 않는다(`restore`는 `addDeltas` 예외만 처리). 역시 다음 시작 시 보정된다.
- **서버 여러 대:** 한 서버가 재시작하며 전체 재집계를 할 때 다른 서버의 반영 전 증감분(최대 한 주기)과 겹쳐 이중 반영될 수 있다. 현재 단일 인스턴스라 해당하지 않는다.
- **시작 시 전체 재집계:** 기존 SQL을 쓰므로 실행 중에는 좋아요 쓰기를 잠글 수 있다. 웹 서버가 뜨기 전에 돌아서 요청과 겹치지 않는다. 운영 중 재실행은 지원하지 않는다.
- **운영 DB 스키마:** `ddl-auto: none`이라 유니크 키 순서 변경은 엔티티 정의만 바꿨다. 운영 반영에는 별도 스키마 변경 절차가 필요하다.
- **`INSERT IGNORE`:** 중복 외 오류(NOT NULL, 값 잘림 등)도 경고로 넘길 수 있다. 현재 모든 컬럼을 값으로 채우고 FK가 없어 영향이 작다.
- **`on duplicate key update` 반응 범위:** 모든 유일 키 충돌에 반응한다. `product_like_counts`에 유일 키를 추가할 때 다시 확인한다.
- **R04로 넘기는 것:** 좋아요순 정렬 인덱스와 `COUNT` 비용, 좋아요 수의 저장 위치(`product_like_counts` 유지 또는 `products` 비정규화).
- **`docs/test` 테스트 개수:** 에이전트가 수기로 계산했고, 실제 개수와 대조하지 않았다.
- **실행 SQL 수 전후 비교:** 주문 목록과 INSERT IGNORE 외에는 테스트 로그로 체계적으로 비교하지 않았다. EXPLAIN은 R04에서 다룬다.

## 5. 회고

- **문답이 설계를 바꿨다.** 좋아요 집계는 "변경된 상품 재집계"에서 출발했다. 사용자의 워터마크 제안, "스프링 캐시" 제안, 참고 블로그 검토를 거치며 "메모리 증감분 + 커밋 후 이벤트"로 좁혀졌다. 규모 가정(좋아요 수백만, 인기 상품 편중)을 초반에 합의한 것이 인기 상품 재COUNT 비용을 드러내 방향을 정하는 기준이 됐다.
- **확인하지 않은 전제가 되돌림을 만들었다.** 에이전트의 "응답 에러 코드가 바뀐다" 보고를 코드로 확인하지 않고 전달해, 필요 없는 되돌림 커밋 두 개가 생겼다. 응답 계약 변화는 예외 타입이 아니라 `ApiErrorMapper`를 거친 실제 응답으로 확인해야 한다.
- **추천을 철회한 사례:** `useAffectedRows=true`는 영향 범위(전역 연결 설정, Hibernate의 UPDATE 행 수 검사)를 확인하기 전에 추천했다가 철회했다. 전역 설정을 추천할 때는 먼저 영향 범위를 확인한다.
- **위임은 효과적이었고 검토는 필요했다.** 구현 20개 커밋을 Sonnet에 세 번 위임했다. 중단 조건(HQL/INSERT IGNORE 반환값, ArchUnit 배치)을 명시해 두어 임의의 대체 구현이 없었다. 반면 계획에 없던 세부(UseCase 메서드 이름, Row 가시성)는 결과 검토에서 드러났다.
- **가벼운 테스트 원칙을 지켰다.** 동시성·slow 테스트를 추가하지 않고, 도메인·Service는 단위 테스트로, SQL 동작만 최소 통합 테스트로 확인했다.
