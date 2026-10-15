# 조회 성능·캐시 설계 (W4)

> **이 문서의 자리** — W4의 Plan·Design 산출물이다. W2 `docs/week2/plan.md`(조회 계약 7장, 구조 5-2)와 W3 `docs/week3/plan.md`(잠금·트랜잭션 경계)에서 이어진다.
>
> **목표** — 이미 동작하는 고객 상품 목록이 데이터·요청 증가를 감당하는지 **측정으로 확인**하고, 같은 결과를 더 적은 비용으로 읽는다. 캐시를 붙였다면 변경·rollback·Redis 장애 뒤에도 같은 업무 결과를 돌려준다.
>
> **진행 방식** — 순진한 구현 → 실패 장면을 테스트로 깨뜨리기 → 고치기. 커밋 이력이 그 순서다 (8장).
>
> **표기** — ✅ 합의 · 🔧 AI 기본값 · 🤔 결정 필요.

## 0. 체크리스트 — 모두 통과해야 한다

| # | 체크리스트 (과제 원문 요약) | 증거 | 상태 |
|---|---|---|---|
| C-1 | 기존 모델·필터·정렬·페이지·삭제 조건을 유지한 실제 API를 개선했다 | `ProductQueryPersistenceAdapter` (3장), 기존 `ProductApiE2ETest`·`ProductQueryPersistenceAdapterIntegrationTest` 통과 + `pagedSortsReturnLikeCountPerProduct` | ✅ |
| C-2 | 같은 데이터에서 SQL 수·실행 계획·결과·지연을 전후 비교했다 | 2장 표 (seed 1만, 응답 JSON 비교 동일) | ✅ |
| C-3 | 좋아요 수 조회와 likes_desc의 전체 정렬 의미를 확인했다 | 2-3, ADR-W4-01 (likes_desc는 전체 후보를 센 뒤 페이지 선택 — 쿼리 유지) | ✅ |
| C-4 | 한 공통 조회의 miss/hit 결과가 맞고 비싼 원본 호출이 줄었다 | `ProductListCacheIntegrationTest.hitReturnsSameResultWithoutListQueries`, 2-4 측정 (hit 시 SQL 1개, PK 조회) | ✅ |
| C-5 | 키·TTL·사용자별 정보와 모든 영향 쓰기 경로를 정리했다 | 4장 (키·TTL), 5장 (쓰기 경로 표) | ✅ |
| C-6 | 정상 관리자 변경·삭제·rollback의 기존 계약을 지키고, 무효화 실패·늦은 채움에서 선택한 신선도·복구 정책과 필수 보호 조건을 검증했다 | `ProductListCacheIntegrationTest` 쓰기 반영 6건(과거 주문 금액 유지 포함), `ProductListCacheFailureTest` 6건, `ProductListDbFailureTest` 2건 (6장) | ✅ |
| C-7 | 실제 Redis 장애에서 DB 우회와 복구 후 재채움을 확인하고 실험 자원을 정리했다 | `ProductListCacheOutageTest` (전용 컨테이너 중단·재시작, `@AfterAll` 정리) | ✅ |
| C-8 | 관련 회귀·lint·ArchUnit을 실행하고 설계의 비용과 한계를 기록했다 | `./gradlew :apps:commerce-api:test` 259건 실패 0, `./gradlew check -x test` 통과, ArchUnit 3건, 7장 비용·한계 | ✅ |

## 1. 조회 계약 — 바꾸지 않는 것 ✅

`GET /api/v1/products?brandId=&sort=&page=&size=` (W2 7장, P-08·P-09)

| 항목 | 계약 |
|---|---|
| 필터 | `brandId` 선택. 없으면 전체 |
| 정렬 | `latest`(기본) · `price_asc` · `likes_desc`. 동률은 모두 **id 내림차순**(최신 등록순, P-09) |
| 페이지 | `page` ≥ 0 (기본 0), `size` 1–100 (기본 20), 범위 밖·알 수 없는 sort는 400. 페이지 번호 방식 유지 (커서로 바꾸지 않음) |
| 노출 | 삭제된 상품 제외. 삭제된 브랜드의 상품은 W3 일괄 삭제로 함께 삭제되어 제외 |
| 응답 필드 | `id, name, price, brand{id, name}, likeCount` + `page, size, totalElements`. 재고는 보여 주지 않음. 사용자별 필드 없음 |

## 2. 측정

### 2-1. 환경과 방법

- 로컬 Docker MySQL 8.0.46, 앱 1대(로컬 프로파일, SQL 로그 출력 켜짐), seed `CatalogSeeder` — 브랜드 100, 상품 10,000(삭제 103·품절 200), 사용자 1,000, 좋아요 90,630행. 가격 합계 472,685,000으로 과제 검증값과 일치.
- 측정 요청: ① `brandId=7&sort=price_asc` 첫 페이지(size 20, 브랜드 7의 판매 상품 99개) ② `sort=likes_desc` 첫 페이지(전체).
- 도구: `EXPLAIN`, `EXPLAIN ANALYZE`, `Handler_read_*`(FLUSH STATUS 뒤 실행), curl 지연(준비 30회 버린 뒤 200회 × 2번).
- 결과 동일성: 개선 전 응답 JSON을 저장해 두고 개선 후·캐시 hit 응답과 `content`·페이지 정보를 통째로 비교.

### 2-2. 기준선 (개선 전)

| | price_asc (브랜드 7) | likes_desc (전체) |
|---|---|---|
| 요청당 SQL | 3 (목록+좋아요 JOIN, 건수, 브랜드) | 3 |
| 계획 | `ref idx_products_brand`, `Using where; Using temporary; Using filesort` | `Index scan on PRIMARY` → 좋아요 조인 → 집계 → Sort |
| 읽은 행 | 상품 100 · 좋아요 948 | 상품 10,000 · 좋아요 91,231 |
| Handler `read_key` / `read_next` | 100 / 1,043 | 9,898 / 99,794 |
| DB 시간 (ANALYZE) | 0.8 ms | 30 ms |
| HTTP p50 / p95 | 4.4 / 5.4 ms, 15.6 / 30.9 ms (두 실행) | 24.0 / 26.2 ms, 23.3 / 24.3 ms |

N+1은 없었다 — 좋아요는 JOIN 한 번, 브랜드는 `IN` 한 번.

### 2-3. 인덱스·쿼리 개선 — 깨뜨리기 → 고치기

| 단계 | 정렬 | 상품 인덱스에서 읽은 항목 | 해석 |
|---|---|---|---|
| 기준선 `(brand_id)` + JOIN 쿼리 | temporary + filesort | 100 | 인덱스에 가격이 없어 브랜드 구간 전체를 읽고 정렬 |
| A. `(brand_id, deleted_at, price, id)` 추가, 쿼리 그대로 | **temporary + filesort 그대로** | 99 | JOIN + GROUP BY가 임시 테이블을 만들어 인덱스 순서를 버린다 |
| B. 같은 인덱스 + 상품만 고르는 쿼리 | filesort 남음 | 99 | 동률 정렬 `id DESC`와 인덱스의 `id ASC` 방향이 어긋남 |
| C. **`(brand_id, deleted_at, price, id DESC)`** + 상품만 고르는 쿼리 | **없음** | **20** (`read_key` 1, `read_next` 19) | 채택 |

채택 후 price_asc 한 요청: 상품 20행 + 좋아요 `IN` 집계 185행(`Covering index range scan on likes`), 결과 동일.

| | 전 | 후 |
|---|---|---|
| 응답 (상품·순서·좋아요 수·페이지) | 기준 | **동일** |
| 상품 / 좋아요 인덱스 읽기 | 100 / 948 | 20 / 185 |
| 정렬·임시 테이블 | 있음 | 없음 |
| 요청당 SQL | 3 | **4** (좋아요 집계가 따로 나감) |
| HTTP p50 | 4.4 · 15.6 ms | 10.1 · 8.1 ms |

**HTTP 시간은 개선이라고 말하지 않는다.** 같은 측정이 실행마다 2~3배 흔들렸고, 1만 행에서는 DB 시간 자체가 1 ms 미만이다. 줄어든 것은 읽는 양이고, 브랜드당 상품이 많을수록 차이가 커진다(브랜드 100 고정이면 상품 100만 개에서 브랜드당 1만 개 — 측정하지 못함, 7장).

브랜드 필터가 없는 조회는 이 인덱스를 쓰지 못한다 — price_asc 전체는 테이블 스캔 + 정렬(1만 행, 약 4.5 ms, 리뷰어 실측), latest 전체는 PK 역순 스캔이라 정렬이 없다. 과제의 측정 대상은 브랜드 필터 + 가격순이라 그대로 두었다.

likes_desc는 정렬 기준이 집계 결과라 인덱스로 순서를 미리 만들 수 없다. 페이지를 먼저 고르고 세면 "전체 후보 중 좋아요 많은 순"이 아니게 되므로 쿼리를 유지했다(ADR-W4-01).

### 2-4. 캐시 hit (seed 1만, 로컬 Redis master)

| | price_asc (브랜드 7) | likes_desc (전체) |
|---|---|---|
| hit 요청의 SQL | 1 — `select id from products where id in (20개) and deleted_at is null` | 1 — 같음 |
| HTTP p50 / p95 | 5.8 / 8.7 ms, 5.5 / 7.3 ms | 5.7 / 7.2 ms, 5.1 / 6.6 ms |
| 응답 | 기준선 JSON과 동일 | 기준선 JSON과 동일 |

likes_desc는 hit에서 상품 1만·좋아요 9만 행 집계(DB 30 ms)가 PK 20건 확인으로 바뀌어 p50이 약 24 → 5 ms. price_asc는 원래 싸서 차이가 작다 — 캐시를 붙인 근거는 likes_desc다.

## 3. ADR-W4-01. 정렬마다 쿼리를 나눈다 ✅

- **선택** — latest·price_asc: 인덱스 순서로 상품 페이지를 먼저 고르고(`LIMIT`), 그 상품들의 좋아요만 `product_id IN (…) GROUP BY`로 센다. likes_desc: 기존 `LEFT JOIN likes … GROUP BY … ORDER BY count DESC, id DESC`를 유지한다.
- **인덱스** — `idx_products_brand_deleted_price_id (brand_id, deleted_at, price, id DESC)`. `=` 조건 두 열 뒤에 정렬 열을 **같은 순서·방향**으로 둔다. `deleted_at IS NULL`은 MySQL이 등호처럼 인덱스 탐색에 쓴다. 적용 SQL은 `docs/week4/sql/product-indexes.sql`, 테스트 DB는 `ProductModel`의 `@Index`(연습으로 하나만 엔티티에 둠).
- **비용** — 요청당 SQL이 하나 늘었다. 인덱스 하나만큼 쓰기·저장 비용. 관리자 화면처럼 삭제 조건이 없는 조회는 이 인덱스로 정렬을 피하지 못한다(인덱스는 쿼리별 계약에 맞춘다).
- **다른 쿼리의 계획이 바뀐다 (리뷰에서 발견)** — 이 인덱스가 생기자 W3 브랜드 일괄 삭제의 `WHERE brand_id = ? AND deleted_at IS NULL ORDER BY id FOR UPDATE`가 이 인덱스 + `Sort: id`로 바뀌었다. InnoDB는 읽는 순서대로 잠그므로 잠금이 가격순이 되어 ADR-W3-03(상품 id 오름차순)이 깨졌다. `(brand_id, deleted_at, id)` 인덱스로 바꿔도 옵티마이저가 가격순 인덱스를 골랐다(실측). 그래서 잠금 조회를 "대상 id를 잠금 없이 고른 뒤 PK `IN … ORDER BY id FOR UPDATE`"로 바꿨다 — 주문 확정과 같은 방식이고 인덱스에 의존하지 않는다. 사이에 새 상품이 끼지 않는 것은 브랜드 행 잠금(R-11) 덕분이다. `LockingSqlIntegrationTest`가 잠금 SQL이 PK 조회인지 확인한다.
- **다시 열 조건** — likes_desc가 캐시로도 감당이 안 되면 좋아요 수 비정규화(상품 행의 `like_count`)를 검토한다. 정합성 조건: 관계가 **실제로** 추가·삭제됐을 때만 ±1 (지금의 `INSERT … ON DUPLICATE KEY UPDATE`는 드라이버 설정에 따라 이미 있던 좋아요에도 영향 행 1을 돌려줄 수 있어 확인 필요), 상품 행은 W3에서 주문 확정·관리자 수정이 `FOR UPDATE`로 잠그므로 인기 상품의 좋아요가 그 잠금과 줄을 선다.

## 4. ADR-W4-02. 캐시 범위·키·TTL ✅

- **범위** — 고객 상품 **목록** 응답 한 페이지(브랜드·정렬·페이지·크기별). 상세는 캐시하지 않는다(원본 쿼리가 PK 조회라 싸다). 주문 확정·재고·포인트 판단은 캐시를 쓰지 않는다 — 기존 DB 규칙 그대로.
- **값** — `ProductListPage(content: List<ProductInfo>, page, size, totalElements)` JSON. Spring `PageImpl`은 그대로 역직렬화되지 않아 필요한 값만 담는다. 사용자별 필드가 없으므로 공용 키로 충분하다.
- **키** — `product:list:v1:g{세대}:brand={id|all}:sort={SORT}:page={n}:size={n}`. `v1`은 직렬화 형식 버전(형식을 바꾸면 올림 — DB 변경 감지용이 아님). 세대는 ADR-W4-04.
- **TTL** — 🔧 60초, 고정 만료(TTL). 읽을 때 연장하지 않는다(TTI 아님 — 자주 읽히는 옛 값이 계속 남지 않게). 무효화가 실패했을 때 옛 표시 값의 상한이자 옛 세대 키의 정리 수단이다.
- **연결** — master 연결로 읽고 쓴다(ADR-W4-04). 기본 연결(REPLICA_PREFERRED)은 쓰지 않는다.

## 5. ADR-W4-03. 변경 책임 — 목록을 바꾸는 쓰기 경로와 commit 뒤 무효화 ✅

| 쓰기 (기존 API·유스케이스) | 목록에서 바뀌는 것 | 무효화 |
|---|---|---|
| 관리자 상품 등록 `ProductCommandService.create` | 구성·건수·최신순 | ✅ |
| 관리자 상품 수정 `update` (이름·가격) | 이름·가격·가격순 | ✅ |
| 관리자 상품 삭제 `delete` | 구성·건수 (비노출) | ✅ |
| 관리자 재고 변경 `changeStock` | 없음 — 목록에 재고가 없다 | ✗ (의도) |
| 관리자 브랜드 수정 `BrandCommandService.update` | 브랜드 이름 | ✅ |
| 관리자 브랜드 일괄 삭제 `delete` | 그 브랜드 상품 전부 비노출 | ✅ |
| 브랜드 등록 | 없음 (상품이 아직 없음) | ✗ |
| 좋아요 등록·취소 `LikeCommandService.like/unlike` | 좋아요 수·좋아요순 | ✅ (멱등 재요청도 무효화 — 정확성 우선, hit율 비용) |
| 주문 확정 (재고·포인트) | 없음 | ✗ |

- **경계** — 쓰기는 사실만 이벤트로 발행하고(`ProductChangedEvent`·`BrandChangedEvent`·`ProductLikesChangedEvent`), `ProductListCacheEventListener`가 `@TransactionalEventListener(AFTER_COMMIT)`로 받아 무효화한다. rollback이면 실행되지 않는다. 트랜잭션 밖에서 발행되면 기본값은 조용히 무시하므로 `fallbackExecution = true`로 즉시 실행한다. commit 전에 지우면 그 사이 조회가 commit 전 원본(옛 값)을 다시 채운다 — 실패 장면 테스트로 재현했다.
- **무효화 실패** — afterCommit에서 예외를 던지면 이미 commit된 요청이 500이 된다. 잡아서 기록만 하고, 그 목록은 TTL까지 옛 값일 수 있다(6장 정책).
- **쓰기 트랜잭션 안의 조회는 캐시를 거치지 않는다** — 같은 트랜잭션이 읽은 commit 전 값을 캐시에 넣으면 rollback 뒤 존재하지 않는 결과가 남는다(실패 장면으로 재현: 9,000원이 남음).
- `@Transactional` 하나가 MySQL과 Redis를 원자적으로 묶지 못한다. 그래서 실패 창을 TTL로 제한하고, 신선도로 허용하지 않는 것(비노출)은 원본으로 확인한다.
- **ADR-11 예외 (W2)** — `ProductListQueryService`는 트랜잭션을 열지 않는다. Redis를 기다리는 동안 DB 커넥션을 잡지 않으려는 것이다. 경로에 따라 트랜잭션이 0~2개다 (hit: 비노출 확인 1개 / hit인데 팔 수 없는 상품이 섞임: 확인 + 목록 2개 / miss: 목록 1개). 두 트랜잭션이 다른 스냅샷을 봐도 반환하는 것은 원본에서 다시 읽은 결과다. ✅
- **afterCommit의 Redis 호출 동안 DB 커넥션을 쥐고 있다** — Spring은 afterCommit 뒤에 커넥션을 반납한다. Redis가 느리면 쓰기마다 최대 200 ms 더 쥔다. 행 잠금은 commit 때 이미 풀려 경합은 아니다.
- **기능 간 의존 — 이벤트로 바꿨다** ✅ (v0.3) — 처음엔 brand·like가 product의 응용 클래스 `ProductListCacheInvalidator`를 직접 주입받았다(브랜드가 상품 캐시 사정을 앎). 비교한 안: (a) 그대로 (b) brand 쪽 출력 포트를 product가 구현 (c) 이벤트. (c)를 골랐다 — 발행하는 쪽은 사실만 알리고, 무효화할 캐시가 늘어도 brand·like가 바뀌지 않는다.
  - 브랜드 이벤트는 `brand.domain`에 둔다. 리스너는 product → brand 방향이라 원래 흐름과 같다.
  - 좋아요 이벤트는 `like.domain`에 두면 product가 like를 알게 돼 like → product → like 순환이 생긴다(ArchUnit 실패로 확인). 검사 규칙을 넓히는 대신 "이 상품의 좋아요 수가 바뀌었다"는 상품 쪽 사실 `product.domain.ProductLikesChangedEvent`를 like가 발행한다 — like → product는 원래 허용된 방향이다.
  - 비용: 발행하는 코드만 읽어서는 캐시가 비워진다는 것이 보이지 않는다(pub/sub의 간접성). 연결은 쓰기 경로마다 "쓰기 → 목록 반영" 장면 테스트가 지킨다 — 리스너를 빠뜨리면 그 테스트가 실패한다.
- **무효화가 필요 이상으로 잦다** — 세대가 전역 하나라 상품 하나의 좋아요가 모든 목록 키를 버린다. 이미 좋아요한 상태의 재요청·없는 좋아요 취소(멱등, ADR-08)와 브랜드 설명만 바꾼 수정도 무효화한다(`LikePort`가 바뀐 행 수를 돌려주지 않음). 정확성 문제는 아니고 hit율 비용이다. 🤔 (a) 바뀐 행 수가 0이면 무효화하지 않기 (b) 좋아요 수를 TTL 신선도에 맡기기(과제의 "영향 쓰기 경로 모두 반영"과 충돌하는지 판단 필요) (c) 브랜드별 세대 — 전체 목록 키는 어떤 쓰기에도 무효화돼야 해 효과가 제한적. 부하 측정으로 hit율을 본 뒤 정한다.
- **seed는 무효화를 거치지 않는다** — JDBC로 직접 넣는다. 측정 전에 Redis의 목록 키가 남아 있으면 seed 직후 최대 60초 옛 목록이 보일 수 있다. seed 뒤 `redis-cli FLUSHDB`(로컬 전용)를 실행한다.

## 6. ADR-W4-04·05. 신선도와 장애 계약 ✅

### ADR-W4-04. 늦은 채움은 세대 키로 막는다

- **장면** — ① 조회가 DB에서 옛 결과를 읽음 → ② 관리자 변경 commit + 무효화 → ③ 조회가 옛 결과를 캐시에 저장 → ④ 새 조회가 옛 결과를 받음. 삭제(DEL) 방식은 ②의 삭제가 ③보다 먼저라 막지 못한다.
- **선택** — 목록 키에 세대를 넣는다. 조회는 **DB를 읽기 전에** 세대를 읽고 그 세대로 찾고 저장한다. 쓰기는 commit 뒤 세대를 `INCR`한다. ③의 옛 결과는 옛 세대 키에 들어가고, ④는 새 세대 키를 쓰므로 보지 않는다. 옛 세대 키는 TTL로 사라진다.
- **세대 키가 사라지면** — 현재 시각(ms)으로 다시 시작(`SETNX`). 1부터 세면 TTL이 남은 옛 세대와 번호가 겹칠 수 있다. 쓰기가 먼저 `INCR`해 1부터 시작해도 시각 기반 옛 세대와는 겹치지 않는다.
- **master에서 읽는 이유** — 세대는 "무엇이 최신인가"를 정하는 포인터다. 복제가 늦은 replica에서 옛 세대를 읽으면 쓰기 직후 순차 조회에 옛 목록을 준다.
- **비용** — 조회마다 Redis 왕복 2번(세대 + 목록). 모든 쓰기가 목록 전체를 무효화한다(좋아요 한 번에도) — 쓰기가 잦으면 hit율이 떨어진다. 옛 세대 키가 TTL 동안 메모리에 남는다.
- 대안으로 본 것: DB에 revision 행을 두고 hit마다 확인(권위 있는 버전) — 모든 쓰기가 한 행을 갱신해 핫 로우가 되고, 좋아요가 그 행 잠금에 줄을 선다. 지연 이중 삭제 — 창을 줄일 뿐 없애지 못한다.

### ADR-W4-05. 표시 신선도·비노출·Redis 장애

| 상황 | 정책 | 증거 |
|---|---|---|
| 정상 변경 완료 뒤 시작한 순차 조회 | 새 결과 | `ProductListCacheIntegrationTest` 쓰기 반영 6건 |
| 무효화(세대 `INCR`) 실패 | 관리자 변경은 성공으로 남는다. **이름·가격·브랜드 이름·좋아요 수는 TTL(60초) 동안 옛 값을 허용**, 만료 뒤 원본과 같아지면 복구 완료 | `failedInvalidationKeepsCommitAndRecoversByTtl` (테스트 TTL 1초) |
| 삭제된 상품 | **신선도로 허용하지 않는다.** hit여도 담긴 id가 아직 팔 수 있는지 PK `IN` 조회로 확인, 하나라도 아니면 miss로 다뤄 새로 채움 | `deletedProductIsNeverServedFromStaleCache` |
| Redis 느림·중단 | 명령 대기 200 ms·연결 200 ms·끊긴 동안 명령 즉시 거절. 캐시 호출 실패는 DB로 우회, 그 요청은 캐시를 채우지 않음 | `ProductListCacheOutageTest` (2초 안에 DB 결과, 쓰기 성공, 재시작 뒤 재채움) |
| DB 오류 | **감추지 않는다** — 빈 목록·가격 0·캐시 값으로 대신하지 않고 기존 오류 계약대로 전달 | `ProductListDbFailureTest` 2건 |
| 주문 확정 | 캐시를 쓰지 않는다 — DB의 주문 단가·재고·포인트 규칙 그대로 | 변경 없음 |

- 대기 시간은 `RedisConfig`에서 정한다. 이 저장소는 `LettuceConnectionFactory`를 직접 만들어 `spring.data.redis.timeout`이 적용되지 않는다(Lettuce 기본 명령 대기 60초). 200 ms는 명령 하나의 상한이고, 한 조회가 Redis를 부르는 횟수(세대 읽기, 없으면 시작·재조회, 목록 찾기, 저장)만큼 쌓일 수 있다 — 연결이 살아 있는데 응답만 없는 최악의 경우 한 요청이 약 1초까지 기다린다. 연결이 끊긴 경우는 즉시 거절이라 첫 호출에서 바로 DB로 간다. 이 상한은 `modules:redis`를 쓰는 다른 앱에도 함께 적용된다.
- DB 오류를 감추지 않는다는 것은 `ProductListDbFailureTest`가 확인한다: miss의 목록 조회 실패, hit의 비노출 확인 실패 모두 오류를 그대로 전달한다(캐시 값으로 대신 응답하지 않음).
- 장애 중 쓰기의 무효화도 실패한다. Redis가 **데이터를 유지한 채** 돌아오면(로컬 compose는 AOF 켜짐) 그 목록은 TTL까지 옛 값일 수 있다 — 위 "무효화 실패" 정책과 같다. 테스트는 영속화를 끈 Redis라 재시작 뒤 비어 있다.

## 7. 비용과 한계

- 측정 규모는 상품 1만 개뿐이다. 10만·100만 단계는 하지 못했다 — 브랜드당 상품이 늘 때 price_asc 개선 폭, likes_desc DB 시간의 증가는 예상일 뿐이다.
- 지연은 로컬 단일 머신·SQL 로그 출력 상태에서 curl 순차 요청으로 쟀다. 동시 요청·처리량·커넥션 대기는 재지 않았다. 같은 측정이 실행마다 2~3배 흔들려 ms 차이는 결론에 쓰지 않았다.
- replica 복제 지연 장면은 테스트하지 못했다(테스트 Redis는 단일 노드). master 연결을 쓰는 이유는 설계 판단이다.
- 캐시 hit에도 DB 조회 1개(PK `IN`)가 남는다. 비노출을 지키는 비용이다.
- 같은 키의 동시 miss를 합치지 않는다(stampede 대응 없음). 만료 시점 분산(TTL jitter)도 없다.
- 로컬 실행은 `ddl-auto: none`이다. seed를 다시 넣으려고 `--spring.jpa.hibernate.ddl-auto=create`로 띄우면 테이블이 다시 만들어지며, `@Index`에 없는 SQL 인덱스는 사라진다 — `docs/week4/sql`의 SQL을 다시 실행한다. 테스트도 같은 로컬 MySQL을 쓰지만 스키마는 `loopers_test`로 나눠, 테스트를 돌려도 `loopers`의 seed는 남는다 (`docker/mysql-init/01-test-schema.sql`, 기존 볼륨이면 root로 한 번 실행).

## 8. 커밋 이력 — 깨뜨리기 → 고치기

| 커밋 | 내용 | 실패 장면 |
|---|---|---|
| `perf:` | 정렬별 쿼리 분리 + 인덱스 | — |
| `feat:` | 순진한 cache-aside (트랜잭션 안에서 삭제, 오류 처리 없음, replica 우선) | — |
| `test:` | 실패 장면 6건 | 6건 실패 |
| `fix:` commit 뒤 무효화 | afterCommit, 무효화 실패를 쓰기 실패로 만들지 않음, 쓰기 트랜잭션 안 조회는 캐시 우회 | 3건 남음 |
| `fix:` 세대 키 | 늦은 채움 방지, master 연결 | 2건 남음 |
| `fix:` hit의 비노출 확인 | 삭제 상품 PK 확인 | 1건 남음 |
| `fix:` Redis 장애 | DB 우회, 대기 상한 | 0건 |
| `test:` / `fix:` 리뷰 반례 | 브랜드 일괄 삭제의 잠금 순서 회귀 → PK 범위 잠금 | 1건 → 0건 |
| `test:` 리뷰 반영 | 빈 캐시 rollback, hit SQL 단언 강화, 과거 주문 금액, DB 오류 전달 | (보강) |
| `fix:` 세대 시작 | 무효화도 세대를 현재 시각부터 시작 | — |

## 9. AI 검토 기록

AI 리뷰어 3명(domain·persistence·test, 읽기 전용)에게 `53f26d2..HEAD`(seed 이후 W4 변경 전체)를 주고 책임 배치·트랜잭션 경계·쿼리 계획·잠금·테스트의 반례를 요청했다. 지적마다 코드와 실제 MySQL로 대조했다.

| # | AI 지적 | 판정 | 근거·조치 |
|---|---|---|---|
| 1 | 새 인덱스가 브랜드 일괄 삭제의 잠금 순서를 가격순으로 바꿔 ADR-W3-03이 깨진다 (persistence, Important) | **채택** | seed 1만에서 `EXPLAIN`으로 `Index lookup … idx_products_brand_deleted_price_id → Sort: p.id` 확인. 제안 (b) `(brand_id, deleted_at, id)` 인덱스는 실측으로 해결되지 않아(옵티마이저가 여전히 가격순 인덱스 선택) (a) PK 범위 잠금으로 고침. 잠금 SQL 단언을 먼저 추가해 실패 확인(test → fix) |
| 2 | rollback 테스트가 지금 코드의 "쓰기 트랜잭션 안 조회는 캐시 우회" 가드를 지키지 못한다 (test, Important) | **채택** | 미리 채운 캐시 때문에 트랜잭션 안 조회가 hit였다. 빈 캐시 변형을 추가하고, 가드를 지우면 9,000이 남아 실패하는 것을 변이로 확인 |
| 3 | hit SQL 단언 `from likes`는 likes_desc 쿼리(`from products left join likes`)에서 늘 참이다 (test, Important) | **채택** | 단언을 "SQL은 PK `IN` 조회 하나뿐"으로 바꿈 |
| 4 | "가격 변경 뒤 과거 주문 금액 유지" 장면이 없다 (test, Important) | **채택** | 확정 주문의 단가·합계·결제액 유지 테스트 추가 |
| 5 | 문서가 "DB 오류는 감추지 않는다"의 증거로 장애 테스트를 들지만 DB 실패를 만드는 테스트가 없다 (test, Important) | **채택** | `ProductListDbFailureTest` 추가 (조회 포트 spy로 DB 실패, miss·hit 모두 오류 전달) |
| 6 | brand·like가 product 응용 클래스를 직접 의존하고 ArchUnit 예외에 묻힌다 (domain, Important) | **채택 (v0.3)** | 5장. 학습자 결정으로 `@TransactionalEventListener(AFTER_COMMIT)` 이벤트로 전환. 좋아요 이벤트는 순환을 피해 product.domain에 둠 |
| 7 | `ProductListQueryService`가 ADR-11(유스케이스 = 트랜잭션 하나)의 예외인데 기록이 없다 (domain, Important) | **채택** | 5장에 예외와 이유 기록 |
| 8 | 좋아요 한 번(멱등 재요청 포함)이 모든 목록 키를 무효화한다 (domain Important, persistence Minor) | **기록 · 🤔** | 정확성 문제가 아니라 hit율 비용. 5장에 대안 (a)(b)(c)와 결정 조건 |
| 9 | 세대 키가 없을 때 무효화가 INCR로 1부터 다시 센다 (persistence·domain, Minor) | **채택** | 무효화도 현재 시각부터 시작. 1 ms당 1번보다 빠른 증가에서는 겹칠 수 있다는 전제를 주석에 명시 |
| 10 | 대기 상한 200 ms가 모듈 전체에 걸리고, 한 요청에서 몇 배로 쌓일 수 있다 (domain·persistence, Minor) | **채택(문서)** | 6장에 최악 대기와 적용 범위, `RedisConfig` 주석 |
| 11 | afterCommit의 Redis 호출 동안 DB 커넥션을 쥔다 (domain·persistence, Minor) | **기록** | 5장 |
| 12 | 상품·브랜드 삭제의 무효화를 지워도 정상 경로 테스트가 통과한다 — hit의 비노출 확인이 가린다 (test, Minor) | **부분 채택** | 브랜드 삭제 테스트에 전체 건수 단언 추가. 캐시된 페이지 밖의 삭제로 건수만 달라지는 장면은 추가하지 않음 |
| 13 | seed가 무효화를 거치지 않는다 (domain, Minor) | **기록** | 5장 (seed 뒤 로컬 Redis FLUSHDB) |
| 14 | 테스트 TTL 1초가 실패 장면 클래스 전체에 걸려 느린 환경에서 거짓 통과 가능, 고정 포트 TOCTOU (test, Minor) | **기록** | 현재 실행 시간은 ms 단위. 흔들리면 TTL 장면을 별도 클래스로 분리 |
| 15 | 세대 방식에서 변경 완료 뒤 시작한 조회가 옛 값을 받는 경로는 없다 — 쓰기는 INCR까지 마친 뒤 응답하고, 그 뒤 조회는 새 세대를 읽는다 (domain) | **확인** | 남는 창은 commit–INCR 사이 수 ms(쓰기가 끝나기 전)와 INCR 실패(TTL 정책)뿐 |

## 변경 이력

| 날짜 | 버전 | 내용 |
|---|---|---|
| 2026-10-15 | v0.1 | 초안 — 조회 계약, 기준선·전후·hit 측정, ADR-W4-01~05, 실패 장면과 커밋 이력 |
| 2026-10-15 | v0.2 | **AI 리뷰 반영** — 브랜드 일괄 삭제 잠금 순서 회귀(PK 범위 잠금), ADR-11 예외, 기능 간 의존·무효화 빈도(🤔), 최악 대기, DB 오류 테스트, 9장 검토 기록 15건 |
| 2026-10-15 | v0.3 | **학습자 결정 반영** — 목록 캐시 무효화를 이벤트(`@TransactionalEventListener(AFTER_COMMIT)`)로 전환, 좋아요 이벤트는 product.domain(순환 회피). 테스트 스키마 `loopers_test` 분리 |
