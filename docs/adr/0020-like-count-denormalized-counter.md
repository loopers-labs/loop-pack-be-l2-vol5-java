# ADR 0020: 좋아요 관계에서 파생한 카운터를 같은 트랜잭션에서 유지한다

Status: Accepted
Date: 2026-10-14
Reviewed: 2026-10-15
Supersedes: [ADR 0002](0002-like-count-is-derived.md)
Amends: [ADR 0017](0017-like-relationship-concurrent-idempotency.md)의 별도 카운터 미사용 결정. 중복·취소·삭제 경합의 외부 계약은 유지한다.

## Context

ADR 0002에서는 반복·동시 요청으로 관계와 카운터가 어긋나는 책임을 추가하지 않기 위해 조회 시 관계를 집계했다. ADR 0017에서는 유니크 제약으로 같은 관계의 중복을 판단하고 조건 DELETE로 취소하여 최초 201·중복 200·취소 204를 유지했다.

week4의 측정 대상은 브랜드 7의 가격순 첫 페이지 20개다. 상품 정렬 인덱스와 좋아요 product_id 인덱스로 읽기를 줄였지만, 같은 페이지에 좋아요 5만 건을 추가한 비교에서 집계 읽기가 93행에서 50,093행으로 증가했다. 이후 같은 인덱스를 가진 관계 집계와 별도 카운터 후보를 일반/핫스팟 데이터에서 비교했다. 핫스팟 읽기 전용 JDBC p95는 두 회차에서 집계 10.730~10.962ms, 카운터 0.805~0.990ms였다. [통제한 SQL 비교 · 100k-read-write-skew.json](../week4/measurements/index-results.json), [카운터 후보 부하 비교 · 100k-counter-experiment.json](../week4/measurements/like-counter-results.json).

이 관찰을 근거로 집계 읽기를 상품별 저장 값 조회로 전환한다. 누적 좋아요 수는 현재 쓰기 요청 빈도나 캐시 hit rate를 증명하지 않는다. 캐시 무효화가 잦다는 가정이나 Redis 효과는 이번 결정의 근거로 사용하지 않는다.

## Decision

`product_like` 관계를 원본으로 유지하고, 파생 저장 상태 `product_like_count(product_id PK, like_count)`를 추가한다. 카운터는 Redis 캐시가 아니며 좋아요 쓰기에 동기적으로 참여한다. Product에 likeCount 컬럼을 추가하지 않는다.

| 책임 | 담당 코드·규칙 |
| --- | --- |
| 상품과 카운터 생성 조정 | ProductFacade.create가 브랜드 확인·상품 생성·카운터 0 초기화를 같은 트랜잭션에서 수행 |
| 좋아요의 의미·쓰기 요청 | LikeService가 LikeRepository에 초기화·등록·취소를 요청 |
| 관계와 카운터의 원자적 저장 | LikeRepositoryImpl이 같은 JDBC/JPA 트랜잭션에서 관계 SQL과 조건부 카운터 UPDATE를 수행 |
| 고객 응답·정렬 조합 | ProductQueryService가 상품·브랜드·카운터 값을 기존 응답 형식으로 조합 |
| 카운터 초기화 스키마·값 | LikeCountModel은 product_id·0 초기값을 표현하고 DB CHECK가 음수를 제한. 이후 증감은 SQL로 처리하며 엔티티를 읽어 재저장하지 않음 |

등록은 INSERT가 실제 성공했을 때만 +1 한다. 기존 사용자/상품 유니크 충돌만 중복으로 처리하고 카운터를 바꾸지 않는다. 취소는 DELETE 영향 건수만큼 -1 하며 없는 관계는 0회 증감한다. `UPDATE ... SET like_count=like_count+? WHERE product_id=? AND like_count+? >= 0`으로 원자적으로 변경한다. 관계 변경과 카운터 변경은 함께 commit/rollback한다. 카운터 UPDATE 영향 행이 1개가 아니면 예외로 전체 변경을 rollback한다.

조회는 요청한 상품 ID의 카운터를 일괄 읽는다. 누락은 0으로 감추지 않고 오류로 처리한다. 양수지만 실제 관계 수와 다른 값을 매 조회 검출하지는 않는다. 그러한 불일치 확인·복구에는 원본 집계 대조와 offline backfill을 사용한다.

논리 삭제 시 카운터와 기존 좋아요 관계를 지우지 않는다. 삭제 상품은 고객 결과에서 제외하고 기존 관계의 본인 취소는 허용한다. ADR 0017의 등록/삭제 경합 결과를 바꾸지 않으며 카운터 갱신을 위해 상품 행의 추가 잠금을 얻지 않는다.

패키지 방향은 interfaces → application → domain ← infrastructure를 유지한다. Product 도메인이 Like 저장소를 호출하지 않는다. cross-domain 생성 조정은 ProductFacade에, DB 증감은 LikeRepositoryImpl에 둔다. 기존 상품 재고·포인트 모델의 읽기 후 변경·저장 방식을 카운터에 그대로 복제하지 않는다.

## Options considered

| Option | Benefit | Cost | 판단 |
| --- | --- | --- | --- |
| 인덱스가 있는 관계 집계 유지 | 별도 카운터 정합성 책임이 없음 | 필요한 상품의 관계 수만큼 집계 | 일반 분포에서는 저렴하지만 hotspot 집계량 증가를 관찰 |
| Product에 likeCount 저장 | 상품 조회 시 값을 함께 가져옴 | 좋아요와 재고·가격 갱신이 같은 상품 행을 사용 | 구현·측정하지 않은 대안. 별도 저장 후보를 선택 |
| 별도 product_like_count | 관계 수와 독립적인 상품별 값 조회, 상품 행을 갱신하지 않음 | 같은 상품 카운터의 쓰기 경쟁·저장·초기화 책임 | 정합성·부하 후보 실험 후 채택 |
| Redis만으로 관계 집계 재사용 | hit에서 원본 집계 생략 가능 | miss/fallback의 집계와 캐시 정합성 책임이 남음 | Redis 비교는 아직 하지 않음. 후속 캐시 설계와 별도 결정 |

## Consequences

검증한 20개 상품 조회에서 관계 집계 50,093행이 카운터 PK 조회 20행으로 바뀌었다. 이것이 트리 탐색·네트워크·API 전체 지연까지 상수라는 뜻은 아니다. 실제 API SQL 캡처도 집계가 카운터 조회로 전환됐음을 확인했다. 별도 JVM·각 30표본 HTTP 비교로 두 분포의 지연이 동등하다거나 개선 배율을 보장하지 않는다.

후보의 hotspot 혼합 부하에서 등록 중앙값 4.531~4.925ms → 6.056~6.140ms, 등록+취소 처리량 346.125~388.750 → 309.500~313.250 ops/s를 관찰했다. 추가 UPDATE와 공유 카운터 행이 생기지만 잠금 대기 시간을 따로 수집하지 않았으므로 차이 전체를 잠금 때문이라고 단정하지 않는다. 카운터 10만 행의 할당량은 약 4.52MiB였다. 테스트 환경 밖 쓰기 빈도·고정 도착률·장기 부하 한계는 미검증이다.

기존 DB는 쓰기 앱을 중지하고 원본 READ/카운터 WRITE table lock 아래에서 삭제·좋아요 0개 상품까지 backfill한다. 재실행 시 COUNT로 덮어쓰며 중복 증가하지 않는다. 원본을 변경하지 않고 누락/불일치·고아를 확인한다. 구버전 쓰기 앱과 새 코드의 동시 운영·무중단 전환은 지원하지 않는다.

## Validation and limits

| 확인한 조건 | 코드·기록 근거 |
| --- | --- |
| 상품/카운터 생성 및 함께 rollback | LikeCounterIntegrationTest.createsZeroCounterWithProductAndRollsBothBackTogether |
| 최초/중복/취소 HTTP 상태와 상세·목록·내 좋아요 값 | preservesHttpStatusesAndAllCustomerCountViews |
| 카운터 누락/감소 실패 시 관계 변경 rollback | missingCounterRollsBackNewRelationshipAndDoesNotMasqueradeAsZero, invalidCounterRollsBackCancellationRatherThanLosingRelationship |
| 외부 트랜잭션 rollback | registrationAndCancellationParticipateInOuterRollback |
| 삭제 후 관계 숨김·본인 취소 | deletedProductRetainsCounterUntilOwnerCancelsHiddenRelationship 및 LikeConcurrencyTest의 삭제 경합 |
| 중복·서로 다른 사용자·등록/취소 경합 | LikeConcurrencyTest의 기존 시나리오 후 전체 카운터/관계 일치 추가 검증 |
| 관계 변경 직후·카운터 변경 직후 예외 주입 | LikeCounterExperimentTest의 JDBC 후보 검증. 실제 API failpoint 테스트와 구분 |
| offline 초기화·재실행·0개·삭제 상품 | offlineBackfillIncludesZeroAndDeletedProductsAndIsIdempotent |
| 기존 업무·의존 방향 회귀 | 최근 전체 check 267건, 실패·오류·skip 0 및 Checkstyle·ArchUnit 기록/XML |

BrandRemovalTransactionTest의 fixture는 카운터 0을 준비하고 좋아요를 LikeService 경로로 등록하도록 바꿨다. LikeConcurrencyTest는 ProductFacade로 생성하고 카운터/관계 일치 검증을 추가했다. 기존 업무 기대값·lint·아키텍처 규칙은 완화하지 않았다. 이 검증을 모든 부하·장애·직접 DB 변경에서 정합성을 보장하는 증거로 확대하지 않는다.

## Evidence

- [LikeCountModel](../../apps/commerce-api/src/main/java/com/loopers/domain/like/LikeCountModel.java), [LikeService](../../apps/commerce-api/src/main/java/com/loopers/domain/like/LikeService.java), [LikeRepositoryImpl](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/like/LikeRepositoryImpl.java)
- [ProductFacade](../../apps/commerce-api/src/main/java/com/loopers/application/product/ProductFacade.java), [LikeCounterIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/like/LikeCounterIntegrationTest.java), [LikeConcurrencyTest](../../apps/commerce-api/src/test/java/com/loopers/application/like/LikeConcurrencyTest.java)
- [후보 검증](../week4/implementation-log.md#counter-experiment), [실제 적용](../week4/implementation-log.md#counter-production), [초기화 원본 · counter-backfill.json](../week4/measurements/like-counter-results.json), [실제 카운터 API · 100k-counter-api.json](../week4/measurements/like-counter-results.json)
