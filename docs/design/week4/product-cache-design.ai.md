# 상품 목록 캐시 설계 (W4) - AI용 상세

> 사람이 읽는 요약은 [product-cache-design.md](product-cache-design.md)다. 이 문서는 구현에 필요한 상세를 담는다.

> 튜닝이 끝난 상품 목록 조회 한 경로에 Redis cache-aside를 붙인다. 무엇을 재사용하고, 무엇이 바뀌면 지우며, Redis가 틀리거나 멈추면 어떻게 동작하는지를 구현 전에 정한다.

근거 자료: 조회 계약·개선 결과는 [`product-query-design.ai.md`](product-query-design.ai.md), 측정 절차는 [`product-list-baseline.md`](product-list-baseline.md), 개념·반례는 `docs/note/week4/캐시-설계-참고.md`, 과제 요구는 `docs/note/week4/실습 과제.md` 3·4장.

## 1. 범위와 전제

| 항목 | 내용 |
|---|---|
| 캐시 대상 | 고객 `GET /api/v1/products`의 `sort=latest`·`price_asc` (브랜드 필터 유무, `page`, `size` 모두) |
| 제외 | `likes_desc`(좋아요마다 순서가 바뀌고, 튜닝으로 이미 약 1.9ms), 상품 상세, 인기상품 top 100, stampede 완화 |
| 계약 | 조회 계약은 `product-query-design.md` 2장 그대로다(정렬·동점·삭제 제외·없는 브랜드는 빈 목록·오류). 캐시는 이를 바꾸지 않는다 |
| 전제 | 튜닝 뒤 이 요청의 SQL 합계는 이미 약 1.2ms(브랜드 7 `price_asc`)다. 캐시가 줄이는 것은 SQL 4개 중 상품 페이지·총 개수·브랜드 조회 3개이고, 절대 시간 이득은 작다. 이득과 새 운영 책임(무효화·장애 처리)을 함께 측정해 기록한다 |

## 2. 캐시 후보 표

| 캐시 후보 | 응답 필드 | 영향을 주는 변경 | 무효화 |
|---|---|---|---|
| 목록 페이지 | 상품 `id·name·price`, 브랜드 `id·name`, `totalElements` (`page·size`는 키에 있음) | 상품 생성·수정·삭제, 브랜드 수정·삭제 | 목록 영역 전체 삭제 |
| (캐시 밖) `likeCount` | 상품별 좋아요 수 | 좋아요 등록·취소, 상품 삭제 | 캐시에 넣지 않고 hit마다 `product_like_counts`에서 읽는다 |

- **`likeCount`를 payload에서 뺀 이유**: 좋아요는 가장 잦은 쓰기다. 넣으면 좋아요마다 영역을 비워야 해 hit율이 무너진다. 읽는 쪽은 PK `IN` 조회 한 번이라 싸다.
- **사용자별 필드는 없다**: 이 응답은 요청자를 식별하지 않는 카탈로그 조회(`CLAUDE.md` 보안 설정 절)라 공용 캐시에 섞일 개인 정보가 없다. 키에 사용자를 넣지 않는다.
- **재고**: 고객 응답에 없으므로 재고 변경·주문 확정은 캐시에 영향이 없다. 주문 확정은 캐시를 읽지 않고 기존 DB 규칙(금액 보존, 재고, 결제)을 그대로 쓴다.
- **주문·결제**: 주문·결제 결과는 캐시 대상이 아니다. 결제 수단 변경(포인트 → PG 결제, week2 D-44)은 캐시 필드·무효화에 영향이 없다.

## 3. 키와 TTL

```
product:list:v1:brand={brandId|all}:sort={latest|price_asc}:page={n}:size={n}
```

- `v1`은 payload 형식 버전이다. 직렬화 계약을 바꾸면 올린다(DB 변경 감지 수단이 아니다).
- 영역 비우기 대상은 접두사 `product:list:`뿐이다. `FLUSHALL`은 쓰지 않는다.
- **TTL**: 기본 60초(설정값). 영역 비우기가 정상 동작하면 TTL은 마지막 방어선이다. 실패 시나리오 테스트는 짧은 TTL로 만료를 확인한다. 값은 측정 뒤 조정할 수 있다.
- 읽을 때 TTL을 연장하지 않는다(TTI 아님). 자주 읽히는 낡은 값이 남지 않게 한다.
- 기본 페이지 크기(20)와 깊은 페이지가 모두 키로 들어가므로, 재사용이 적은 깊은 페이지가 키를 늘릴 수 있다. TTL이 정리 수단이며, 필요하면 `page` 상한을 두는 것은 측정 뒤 판단한다(이번엔 두지 않는다).

## 4. 흐름

```
조회(latest|price_asc)
 ├ 캐시 조회 (Redis 오류 → 로그·메트릭, miss로 취급)
 │   ├ hit : 상품 id들로 product_like_counts 조회 (PK IN, SQL 1개)
 │   │        ├ 모든 id에 행이 있음 → 캐시 payload + likeCount 조합해 반환
 │   │        └ 빠진 id 있음(삭제됨) → 이 캐시를 버리고 아래 miss 경로로
 │   └ miss: 기존 경로(SQL 4개) → 결과 반환 직전 캐시 저장(실패는 무시)
 └ likes_desc → 기존 경로 그대로
```

- **삭제 보호(권위 확인)**: `product_like_counts` 행은 노출 중인 상품만 가지며 삭제와 같은 트랜잭션에서 지워진다(D-33·D-36). hit 때 이 조회로 "아직 노출 중인가"를 DB에서 다시 확인한다. 무효화가 실패해도 삭제된 상품이 응답에 나가지 않는다. hit의 SQL이 0개가 아니라 1개인 이유이며 이 비용을 측정에 포함한다.
- **보호 범위의 한계**: 이 확인은 삭제를 잡는다. 이름·가격·브랜드명·`totalElements`·순서는 잡지 못해 TTL 안에서 낡을 수 있다(6장). 삭제된 상품이 빠져 페이지가 `size`보다 적게 보이는 일은 없다. 빠진 id가 있으면 캐시를 버리고 DB로 다시 읽기 때문이다.
- **브랜드 삭제**: 브랜드가 삭제되면 연결 상품이 함께 삭제되어 카운터 행도 지워진다. 그 브랜드의 목록 캐시는 빠진 id 확인에 걸리거나 빈 목록이다. 브랜드가 삭제된 뒤 캐시된 "빈 목록"은 계약(없는 브랜드는 빈 목록)과 같다.
- **읽기·쓰기 연결**: `redisTemplateMaster`(`ReadFrom.MASTER`)로 읽고 쓴다. 기본 템플릿은 `REPLICA_PREFERRED`라 무효화 직후 replica의 옛 값을 읽을 수 있다. master로 읽어도 DB와 Redis의 원자성은 생기지 않으며 복제 지연이라는 한 가지 원인만 없앤다. 테스트 Redis는 master와 replica가 같은 컨테이너다.

## 5. 변경 책임: 무효화 경계

### 5-1. 쓰기 경로

| 쓰기 | 목록 payload 영향 | 무효화 |
|---|---|---|
| `AdminProductFacade.create` | 목록 구성·`totalElements` | 한다 |
| `AdminProductFacade.update` | 이름·가격, 가격순 위치 | 한다 |
| `AdminProductFacade.delete` | 목록 구성·`totalElements` | 한다 |
| `AdminBrandFacade.update` | 브랜드명 | 한다 |
| `AdminBrandFacade.delete` | 연결 상품 전체 삭제 | 한다 |
| `AdminProductFacade.changeStock`, `OrderFacade.create/confirm` | 없음(재고는 응답에 없음) | 하지 않는다 |
| `LikeFacade.like/unlike` | 없음(`likeCount`는 캐시 밖) | 하지 않는다 |
| `AdminBrandFacade.create` | 없음(상품이 없는 브랜드는 필터 결과가 빈 목록 그대로) | 하지 않는다 |

### 5-2. 방식: 영역 전체 비우기, commit 뒤

- 조합(브랜드×정렬×페이지×크기)이 많아 관련 키만 지우면 빠뜨리기 쉽다. 쓰기가 관리자 작업이라 드물므로 `product:list:` 영역 전체를 지운다(접두사 `SCAN` + `UNLINK`). 비운 직후 모든 목록이 miss가 되는 비용은 7장에서 측정한다.
- `@Transactional` 안에서 직접 지우면 commit 전에 다른 조회가 옛 값을 채울 수 있다(참고 노트 2-1). 그래서 Facade는 트랜잭션 안에서 이벤트를 발행하고, `@TransactionalEventListener(phase = AFTER_COMMIT)`이 commit 뒤에 `evictAll()`을 호출한다. rollback이면 이벤트가 전달되지 않아 캐시를 건드리지 않는다.
- Redis 예외는 어댑터가 잡아 로그·메트릭으로 남기고 삼킨다. DB는 이미 commit되었으므로 관리자 응답은 성공으로 둔다.

### 5-4. 포트 위치와 모양

포트는 그것을 쓰는 계층이 소유한다(DIP). 캐시를 쓰는 것은 유스케이스(`ProductFacade`)와 무효화 리스너뿐이고 domain Service는 캐시를 모르므로, 포트를 application에 두고 infrastructure가 구현한다(헥사고날의 출력 포트와 같은 배치). `Repository`가 domain에 있는 것은 domain Service가 쓰기 때문이며 같은 원칙이다.

```
application.product
  ProductListCache           포트: find(ProductListQuery) / save(ProductListQuery, CachedProductListInfo) / evictAll()
  ProductListQuery           조회 조건 record (brandId|null, sort, page, size)
  CachedProductListInfo        payload record (상품 id·name·price·brand{id,name}, totalElements). page·size는 키에 있어 담지 않음. likeCount 없음
  ProductListItemInfo        고객 목록 항목 (payload 항목 + likeCount). DB 조회·hit 가 같은 타입을 돌려준다
  ProductListChangedEvent      관리자 쓰기가 발행하는 이벤트
  ProductListCacheEvictor    @TransactionalEventListener(AFTER_COMMIT) → evictAll()
infrastructure.product
  ProductListRedisCache      구현: 키 형식·JSON 직렬화·TTL·SCAN+UNLINK·Redis 예외 처리
```

- 포트의 입출력은 application 타입뿐이다. 키 문자열·Redis 타입·TTL은 구현의 세부라 포트에 드러나지 않는다.
- 어댑터는 Redis 접근·역직렬화 실패를 잡아 로그·메트릭을 남기고 "없음"(miss)으로 돌려준다. Facade는 Redis 예외 타입을 모른다. DB 예외는 이 어댑터를 지나지 않는다.
- 고객 목록은 상세와 같은 `ProductDetailInfo`를 쓰지 않고 목록 전용 `ProductListItemInfo`(id·name·price·brand{id,name}·likeCount)를 돌려준다. `ProductDetailInfo`에는 재고·생성/수정 시각이 있는데 payload에는 없어(재고는 주문마다 바뀌어 캐시할 수 없다), 같은 타입을 쓰면 hit 때만 빈 값이 들어가기 때문이다. DB 조회(miss·`likes_desc`)와 hit 모두 이 타입을 만들며, 고객 응답 JSON은 바뀌지 않는다. 고객 목록에 필드를 더하려면 payload와 형식 버전도 함께 바꿔야 한다.
- 이 배치로 `infrastructure → application` 의존이 처음 생긴다. week2 `commerce-design.md` 2-3에 "포트 구현에 한해 허용"으로 반영했다. ArchUnit의 기존 금지 규칙은 그대로다.

### 5-3. 남는 실패 창과 정책

| 장면 | 결과 | 정책 |
|---|---|---|
| commit 뒤 `evictAll` 실패 | 옛 payload가 TTL까지 남음 | 이름·가격·브랜드명·`totalElements`·순서의 낡음을 **캐시 채움 시점부터 TTL까지** 허용한다. 삭제된 상품은 4장 권위 확인으로 노출되지 않는다 |
| 이전 조회의 늦은 채움(이전 DB 읽기 → 변경 commit·무효화 → 이전 결과 저장) | 옛 payload가 새로 채워짐 | 위와 같다. 낡음의 상한은 (이전 조회의 지연) + TTL이다(참고 노트 1-3). 이 상한을 줄이려는 revision 방식은 쓰지 않는다(hit마다 DB 확인이 늘고 쓰기가 revision 행에서 경합) |
| Redis 장애 중 변경 | 무효화 호출이 실패해 옛 키가 부활 | 복구 조건: TTL 만료 또는 다음 성공한 무효화. 복구 완료 판정은 "변경 뒤 조회가 DB와 같은 payload를 돌려줌"이다 |

- 허용 범위는 **표시용 필드**로 한정한다. 삭제 비노출, 좋아요 소유권, 주문 금액·재고·결제 판단은 낡음 허용 대상이 아니다.
- 정상 변경(장애·동시 채움 없음) 뒤 시작한 순차 조회는 반드시 새 결과를 받는다. 이 계약과 위 장애 정책은 섞어 주장하지 않는다.
- 단순 TTL이 모든 요청의 시간 상한이라고 주장하지 않는다.

## 6. 장애 계약

- **대기 제한**: 현재 `RedisConfig`는 timeout을 정하지 않아 Lettuce 기본값(명령 60초)을 쓴다. `modules/redis`에 `connect`·`command` timeout 설정을 추가한다. 값은 상품 목록이 DB 우회로 응답하는 데 허용할 수 있는 수준(예: connect 200ms, command 300ms, 구현 때 확정)으로 둔다. 공용 모듈이라 다른 앱에도 같은 기본값이 적용된다는 영향을 결과에 적는다.
- **miss와 오류 구분**: miss는 정상, Redis 오류는 장애다. 둘을 같은 `null`로 다루지 않고 오류는 별도로 로그·메트릭(카운터)을 남긴다.
- **우회는 원본 조회**: Redis 오류는 DB 경로로 가서 정상 결과를 반환한다. 빈 목록·가격 0으로 바꾸지 않는다. 잡는 예외는 Redis 접근과 payload 역직렬화에 한정하고, DB 예외는 기존 오류 계약 그대로 전파한다(전체 `catch (Exception)` 금지).
- **주문**: Redis 장애로 주문의 결제·재고 처리를 우회하지 않는다. 주문 경로는 캐시를 쓰지 않는다.
- **복구**: Redis가 돌아오면 다음 조회부터 정상 miss → 저장으로 재채움된다. 장애 중 무효화가 실패한 옛 키가 남아 있으면 5-3의 복구 조건을 따른다.
- **시간 초과된 명령은 취소되지 않는다**(구현 중 확인, 10-4): 명령 timeout은 호출자의 대기만 끊는다. 응답 없는 Redis(pause)에 보낸 저장(SET)은 소켓에 남아 있다가 복구 직후 실행될 수 있다. 그 값은 저장을 시도한 시점의 DB 결과이고 실행 시점부터 TTL을 가지므로, 낡음은 5-3의 복구 조건(TTL 만료 또는 다음 무효화) 안에 있다.
- **DB 부하**: Redis가 멈추면 hit이던 요청이 모두 원본으로 간다. 이번에는 작은 통제 부하에서 우회 응답의 동일성과 지연 증가만 확인하고, 동시 요청 제한은 다루지 않는다.

## 7. 비용과 확인할 것

- 줄이는 비용: 상품 페이지·총 개수·브랜드 조회(SQL 4 → 1).
- 늘어나는 비용: hit 때 카운터 `IN` 조회 1개, Redis 왕복, JSON 직렬화, 관리자 쓰기 뒤 영역 비우기(`SCAN`), 비운 직후의 전체 miss, 장애·무효화 실패 처리 책임.
- 확인: 같은 입력의 DB-only·miss·hit 결과 일치, hit 요청의 SQL 수 1(테스트로 고정), 지연 분포(준비·측정 표본 수 기록), 영역 비운 직후의 요청 지연, Redis 중단 중 지연.
- DB 쿼리 개선만으로 충분한지 함께 판단해 `product-list-baseline.md` 5장에 기록한다.

## 8. 검증 시나리오

실제 MySQL·Redis(Testcontainers)를 쓴다. Redis 동작을 전부 Mock으로 대체한 테스트로 끝내지 않는다.

| 장면 | 확인 |
|---|---|
| DB-only·miss·hit | 정렬 id·가격·좋아요 수·페이지 정보 일치, hit의 SQL 1개 |
| 관리자 가격 수정 후 재조회 | 새 가격·가격순 위치 반영, 과거 주문 금액 유지 |
| 캐시에 담긴 상품 삭제·브랜드 삭제 | 고객 결과에서 제외, 다른 브랜드·과거 주문 유지 |
| 상품 생성·이름 수정·브랜드명 수정 | 반영 |
| 좋아요 등록·취소 | hit에서도 `likeCount` 반영 |
| DB 변경 rollback | 이전 결과 유지, 새 데이터로 오인할 캐시 없음 |
| commit 뒤 무효화 실패 | 관리자 응답 성공, DB commit 유지, 삭제 상품 비노출, 짧은 TTL 만료 뒤 DB와 일치 |
| 이전 조회의 늦은 채움 | 래치로 순서를 고정해 옛 재삽입을 재현, 삭제 비노출 유지, 만료 뒤 복구 |
| Redis 중단·재시작 | 제한된 대기 뒤 DB 결과, 재시작 뒤 정확한 재채움 |

기다림은 모두 유한한 timeout을 두고 worker를 정리한다. Redis 중단 실험은 테스트가 실제로 연결한 Testcontainer만 대상으로 하며, 공유 Redis에 `FLUSHALL`을 실행하지 않는다.

## 9. 결정 기록

| 번호 | 결정 | 상태 |
|---|---|---|
| D-37 | 캐시 대상은 `latest`·`price_asc` 목록이다. `likes_desc`·상세는 제외한다 | 확정 |
| D-38 | `likeCount`는 payload에 넣지 않고 hit마다 카운터 테이블에서 읽는다. 이 조회가 삭제된 상품을 걸러내는 권위 확인이다 | 확정 |
| D-39 | 무효화는 관리자 쓰기 commit 뒤 영역 전체 삭제이며, Redis 실패는 삼키고 TTL이 방어한다. revision 방식은 쓰지 않는다 | 확정 |
| D-40 | 표시 필드의 낡음은 TTL(기본 60초)까지 허용하고, 삭제 비노출·주문 판단은 허용하지 않는다 | 확정 |
| D-41 | 캐시는 `redisTemplateMaster`로 읽고 쓴다 | 확정 |
| D-42 | `modules/redis`에 connect/command timeout을 추가한다(공용 모듈 영향 있음) | 확정 |
| D-43 | 캐시 포트는 이를 쓰는 application에 두고 infrastructure가 구현한다. `infrastructure → application`은 포트 구현에 한해 허용한다 | 확정 |

## 10. 구현 결과

### 10-1. Redis timeout (D-42)

| 항목 | 이전(Lettuce 기본값) | 변경 |
|---|---|---|
| 연결 timeout (`datasource.redis.connect-timeout`) | 10초 | 200ms |
| 명령 timeout (`datasource.redis.command-timeout`) | 60초 | 300ms |

- 위치: `modules/redis`의 `redis.yml`(모든 프로필 공통), `RedisProperties`, `RedisConfig`.
- `clientOptions`를 직접 지정하면 Spring Data Redis 기본값인 `TimeoutOptions.enabled()`가 사라지므로 함께 넣었다. 이 옵션이 있어야 연결이 끊긴 동안 쌓인 명령도 명령 timeout 뒤에 실패한다. 그래서 `disconnectedBehavior`는 기본값(`DEFAULT`, 자동 재연결 중 명령 수락)을 유지한다.
- 값이 비면 Lettuce·Spring Data Redis가 null 검사로 기동을 실패시킨다.
- **공용 모듈 영향**: `commerce-api`·`commerce-batch`·`commerce-streamer`가 모두 `redis.yml`을 가져다 쓰므로 같은 값이 적용된다. 현재 batch·streamer는 Redis를 쓰는 코드가 없어 동작 변화는 없다. 이후 300ms를 넘는 명령(큰 키 조회, 블로킹 명령 등)이 필요하면 앱별 값을 따로 둔다.
- 확인: `commerce-api`(`CommerceApiContextTest`, `ProductApiE2ETest` 25건), `commerce-batch`(3건)의 컨텍스트 기동 통과. 제한된 대기는 T6 Redis 중단 테스트에서 측정한다.

### 10-2. 포트·Redis 구현과 조회 연결

- 포트 `ProductListCache`와 구현 `ProductListRedisCache`(키 `product:list:v1:…`, TTL 설정 `product.list-cache.ttl` 60초, `SCAN` 500개 단위 + `UNLINK`). Redis 접근·JSON 변환 오류는 miss/무시로 처리하고 `product.list.cache.errors{operation}` 카운터와 경고 로그를 남긴다.
- `ProductFacade.getList`: `latest`·`price_asc`만 캐시를 본다. miss·hit 모두 `LikeService.countByProductIds`로 좋아요 수를 읽는다. 이 메서드는 집계 행이 있는 상품만 담는다(행이 없으면 0으로 채우던 동작을 뺐다. 노출 상품은 생성 때 0 행을 가지므로 정상 상태에서는 쓰이지 않았다).
  - hit: 빠진 id가 있으면(삭제됨) 캐시 값을 버리고 DB로 다시 읽어 저장한다.
  - miss: DB에서 읽은 노출 상품의 행이 없으면 데이터 깨짐으로 보고 `INTERNAL_ERROR`를 던진다(`brandOf`와 같은 방식). 상품 조회와 집계 조회가 같은 읽기 트랜잭션이라 동시 삭제로는 생기지 않고, 집계 테이블 이행 중(`product-like-counts.sql` 3~4단계) 옛 앱이 만든 상품에서만 생길 수 있다.
- 테스트 정리: 목록을 조회하는 기존 테스트(`ProductFacadeTest`, `ProductListQueryCountTest`, `ProductApiE2ETest`)의 `@AfterEach`에 Redis 비우기를 더했다. 테이블을 비우면 id가 1부터 다시 쓰여 이전 테스트의 캐시가 새 데이터로 hit 될 수 있기 때문이다. 기대값은 바꾸지 않았다.
- 공용 테스트 픽스처 수정: `RedisTestContainersConfig`가 컨테이너 주소를 생성자에서 넣어, `RedisConfig`가 먼저 만들어지면 test 프로필의 `localhost:6379`로 접속했다. MySQL 픽스처처럼 static 블록으로 옮겼다.

| 확인 (`ProductCacheQueryCountTest`, 실제 MySQL·Redis) | 결과 |
|---|---|
| miss SQL / hit SQL (`latest`·`price_asc` × 브랜드 유무) | 4 / 1 |
| miss·hit·비운 뒤 DB 재조회 결과 일치 (id·이름·가격·브랜드·좋아요 수, 페이지 정보) | 일치 |
| `likes_desc` 반복 조회 | 매번 SQL 4 (캐시 안 함) |
| 좋아요 등록·취소 후 hit | 좋아요 수 즉시 반영, SQL 1 |
| 캐시를 비우지 않은 채 상품 삭제 후 조회 | 삭제 상품 제외, 페이지 10개 유지, SQL 5(확인 1 + 재조회 4), 다음 조회는 다시 hit |

### 10-3. commit 뒤 무효화

- `AdminProductFacade.create/update/delete`, `AdminBrandFacade.update/delete`가 트랜잭션 안에서 `ProductListChangedEvent`를 발행한다. 5-1 표에서 "하지 않는다"인 쓰기(`changeStock`, 좋아요, 주문, `AdminBrandFacade.create`)는 발행하지 않고, 그 이유를 메서드 주석에 남겼다.
- `ProductListCacheEvictor`가 `@TransactionalEventListener(AFTER_COMMIT)`에서 `evictAll()`을 호출한다. 캐시 구현이 Redis 오류를 이미 삼키지만, 예상 밖의 `RuntimeException`도 잡아 로그만 남긴다. commit 된 관리자 요청을 실패로 바꾸지 않기 위해서다. 실패 시 응답이 성공으로 유지되는지는 T6에서 확인한다.

| 확인 (`ProductCacheInvalidationTest`, 실제 MySQL·Redis, 브랜드 필터 유무 × 정렬 4개 키를 채운 뒤) | 결과 |
|---|---|
| 가격 수정 | 4개 키 삭제, 새 가격·가격순 위치 반영, 과거 주문 금액(단가·합계) 유지 |
| 이름 수정, 상품 등록 | 4개 키 삭제, 새 이름·새 상품·전체 개수 반영 |
| 상품 삭제 | 4개 키 삭제, 목록·전체 개수에서 제외, 다른 브랜드 상품·과거 주문 유지 |
| 브랜드명 수정, 브랜드 삭제 | 4개 키 삭제, 새 브랜드명 반영 / 연결 상품 전체 제외, 다른 브랜드 유지 |
| 바깥 트랜잭션 rollback | 키 유지, 이전 가격·순서 그대로, DB 변경 없음 |
| 재고 변경·좋아요·주문 생성·브랜드 등록 | 키 유지. 좋아요 수는 캐시 밖이라 그대로 반영 |

### 10-4. 실패·장애 재현

- `ProductCacheFailureTest`(TTL 3초): `@MockitoSpyBean`으로 캐시 구현을 감싼다.
  - 무효화 실패: `evictAll`이 예외를 던지게 했다.
  - 늦은 채움: 첫 `save`를 래치로 붙잡아 "옛 DB 읽기 → 변경 commit·무효화 → 옛 값 저장" 순서를 고정했다.
  - 모든 대기에 10초 상한을 두고 worker를 정리한다.
- `ProductCacheRedisOutageTest`(TTL 5초): 테스트가 실제로 연결한 Redis 컨테이너를 pause/unpause 한다. stop/start는 매핑 포트가 바뀌어 같은 JVM의 다른 테스트가 접속하지 못해 쓰지 않는다. 이를 위해 `RedisTestContainersConfig.container()` 접근자를 추가했다.

| 장면 | 결과 |
|---|---|
| commit 뒤 무효화 실패(예외) | 관리자 수정·삭제 성공, DB commit 유지, 옛 가격은 TTL까지 남고 만료 뒤 새 가격·순서, 삭제 상품은 즉시 비노출 |
| 이전 조회의 늦은 채움 | 무효화 뒤 옛 가격이 다시 저장됨(재현), 만료 뒤 복구, 늦게 저장된 값에 삭제 상품이 있어도 비노출 |
| Redis 중단 중 목록 조회 | DB 결과와 같은 응답. 조회·저장이 각각 300ms에서 시간 초과(로그 기준 약 0.6초), 2초 상한 안. `find`·`save` 오류 카운터 각 +1 |
| Redis 중단 중 관리자 수정 | 2초 안에 성공, DB commit, `evict` 오류 +1. 복구 직후 지우지 못한 옛 가격이 보이고, TTL 만료 뒤 DB와 같게 재채움되어 hit |
| 중단 중 시간 초과된 저장 | 취소되지 않고 복구 직후 실행되어 캐시가 생김(6장에 반영). 값은 저장 시점의 DB 결과 |

- 처음에는 "중단 중 수정 → 중단 중 조회 → 복구"로 옛 키 부활을 확인하려 했다. 그런데 중단 중 조회의 시간 초과된 저장이 복구 직후 새 값으로 덮어 옛 값이 보이지 않았다. 그래서 두 장면을 나눴다.

### 10-5. 리팩토링 (T7 전 검토)

- **트랜잭션 범위를 DB 조회로 좁힘**: `getList` 전체가 `@Transactional(readOnly = true)`였다. readOnly 트랜잭션은 시작할 때 DB 커넥션을 잡으므로(Spring `HibernateJpaDialect.beginTransaction`), 캐시 왕복 동안에도 커넥션을 쥐었다. Redis 중단 때는 요청마다 약 0.6초(조회·저장 timeout) 동안 쥐어, 메인 풀 40개 기준 목록 요청이 초당 약 66건을 넘으면 풀이 말라 다른 API까지 커넥션을 얻지 못한다. 6장 장애 계약(Redis 장애를 DB 장애로 번지지 않게)과 어긋나 바꿨다.
  - miss의 DB 조회(SQL 4개)만 `TransactionTemplate`(readOnly)으로 감싼다. 같은 클래스 안 호출이라 `@Transactional` 프록시가 동작하지 않기 때문이다. 네 조회가 같은 스냅샷을 읽는다는 `likeCountOf`의 전제는 유지된다.
  - 캐시 조회·저장과 hit의 좋아요 수 조회(SQL 1개)는 트랜잭션 밖에서 실행한다.
  - `ProductCacheQueryCountTest.TransactionScope`가 호출 순서와 트랜잭션 여부를 고정한다: 캐시 조회(밖) → 좋아요 수(안) → 캐시 저장(밖) / 캐시 조회(밖) → 좋아요 수(밖). SQL 수(miss 4, hit 1)는 그대로다.
- **payload에서 `page`·`size` 제거**: 키에 이미 있고 hit 때도 요청의 페이지 정보로 결과를 만들어, 저장만 되고 읽히지 않았다. 배포 전이라 형식 버전 `v1`은 유지한다.
- **변환 위치 통일**: 캐시 값 → 결과 변환을 `CachedProductListInfo.Item.withLikeCount`로 옮겨, 결과 → 캐시 값 변환(`CachedProductListInfo.from`)과 같은 곳에 둔다.

### 10-6. 측정과 최종 검사

측정 절차와 전체 표는 [`product-list-baseline.md`](product-list-baseline.md) 5장에 있다(같은 seed, `ProductFacade` 호출 단위, 순차 준비 20·측정 100회 × 3라운드).

| 항목 | 결과 |
|---|---|
| A 브랜드 7 `price_asc` p50 | DB-only 2.0~4.3ms, miss 2.3~4.1ms, **hit 0.65~1.1ms** (SQL 4 → 1) |
| C 전체 `latest` p50 | DB-only 약 2.8ms, miss 약 3.5ms, **hit 0.5~0.64ms** |
| 영역 비우기 p50 | 키 100개 0.4ms, 1,000개 1.6ms, 10,000개 11ms (관리자 응답에 더해짐) |
| Redis 중단 중 A p50 | 621ms (조회·저장 timeout 300ms × 2 + DB) |

- 판단: 지연만 보면 DB 튜닝(SQL 합계 약 1.15ms)으로 충분했다. 캐시는 hit에서 SQL을 4 → 1로 줄여 DB 부하를 낮추지만, 무효화·장애 처리와 표시 낡음이라는 운영 책임을 더한다(baseline 5장).
- 최종 검사: `./gradlew :apps:commerce-api:check` 848건, 실패 0, skip 7(환경변수가 있어야 도는 지연 벤치마크 `ProductQueryLatencyBenchmarkTest` 4건·`ProductCacheLatencyBenchmarkTest` 3건). `--tests '*ProductCache*Test' --tests '*ProductQuery*Test'`는 33건 중 26건 실행·통과, 7건은 위 벤치마크 skip.

