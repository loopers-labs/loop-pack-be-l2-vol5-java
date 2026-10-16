# W4 좋아요순의 좁은 순위 집계 — TDD와 전후 비교

이 문서는 2026-10-16 순위 집계 개선 당시의 TDD·측정 기록이다. 아래 수치는 보존하며 이후 완료한 [서버 재시작 직후 첫 조회](product-list-cold-start-measurement.md)·[TTL·메모리 비교](product-list-cache-ttl-memory.md)와 [최신 통합 검사](../week2/commerce-completion-checklist.md#최신-통합-검사)는 별도로 연결한다.

## 승인 범위와 목표

2026-10-16 사용자가 상품 ID·좋아요 수로 전체 순위를 먼저 계산하고 선정 상품의 상세를 일괄 조회하는 방식의 TDD 구현을 승인했다. 대상은 **고객용 무필터 `likes_desc` 목록**이며 기본 첫 페이지는 20개다. 다른 page/size도 기존 계약을 그대로 따른다. 브랜드 필터·최신순·가격순·상세·관리자·내 좋아요는 기존 조회 경로를 유지한다.

직전 분석에서는 노출 상품 9,812개를 좋아요와 연결해 47,086행을 만들고, 이름·가격·브랜드명·감사 시각까지 포함한 임시 집계를 만든 뒤 20개를 반환했다. 좋아요 인덱스는 이미 사용 중이므로 이번 증분은 인덱스 추가가 아니라 **집계에 포함하는 정보의 폭**을 줄인다.

## 책임과 선택

| 항목 | 처리·선택 이유 |
| --- | --- |
| 저장소 | `ProductQueryRepositoryImpl` 안에서 전체 개수 → 전체 후보 ID/count 순위 페이지 → 선정 ID 상세 IN 조회 → 순위대로 합성한다. 기존 `ProductPageRow`를 재사용한다. |
| 정렬·노출 | 상품·브랜드 미삭제, 좋아요 0개·품절 포함, `likeCount DESC, product.id DESC`, total·페이지·응답 필드를 유지한다. 전체 순위를 정한 다음 페이지를 선택한다. |
| 트랜잭션 | 기존 서비스/저장소 읽기 경계와 캐시 `ProductListReader`의 RR snapshot을 유지한다. 순위·상세 사이 쓰기 커밋에도 같은 연결·스냅샷을 사용하는지 검증한다. 업무 쓰기·잠금 순서는 변경하지 않는다. |
| 캐시 | Redis + DB 버전, payload, TTL 60초, single-flight와 500ms 대기, local OFF는 유지한다. 순위 ID만 Redis에 저장하는 캐시 방식 변경이 아니다. |
| 비용 | 비어 있지 않은 DB-only SELECT는 2→3, 직접 계산하는 캐시 miss는 4→5, hit는 1 그대로다. 결과를 공유받거나 Redis 재확인에서 hit인 요청과 구분하며 추가 상세 조회 비용까지 포함해 측정한다. |
| 버린 대안 | 상품 20개를 임의로 먼저 고른 뒤 좋아요를 계산하면 전체 인기순이 틀리므로 제외한다. 좋아요 카운터 저장은 쓰기·중복·롤백 정합성 책임이 늘어나 이번 증분에 포함하지 않는다. |

## TDD 기대값

| 사례 | 검증할 것 |
| --- | --- |
| RANK-01 | 실제 HTTP·1만 seed에서 size 1/20/100과 여러 페이지의 전체 DTO가 독립 기대값과 같고 count/rank/detail의 SELECT 3회. rank projection은 ID/count, GROUP BY는 ID, 상세 IN의 대상은 해당 페이지 ID와 동일. |
| RANK-02 | 좋아요 0개·동점·품절·삭제 상품/브랜드, 첫/마지막/범위 밖·매우 큰 페이지, 전체 노출 0개. 빈 페이지는 count만 실행하고 IN 빈 목록을 만들지 않는다. |
| RANK-03 | 실제 rank 실행 직후 다른 연결에서 가격·좋아요 변경 또는 상품/브랜드 삭제를 커밋. 진행 중 응답은 기존 snapshot, 다음 조회는 변경 결과. 같은 연결·실제 RR·읽기 TX를 관찰한다. |
| RANK-04 | 브랜드 필터·다른 정렬·상세·관리자·내 좋아요·기존 캐시/장애/변경·W3 동시성 회귀를 유지한다. SQL 관측 기대값은 새 경로를 정확히 나타내며 응답 기대값을 완화하지 않는다. |
| RANK-05 | 동일 seed·DDL의 기존 HTTP 측정과 실제 Redis miss/hit 반복을 변경 전후 실행하고 SQL·계획·p50/p95·전체 결과를 비교한다. 기존 raw 증거는 보존하고 고정 ms 합격선을 만들지 않는다. |

## 실행 기록

| 단계 | 실제 결과 |
| --- | --- |
| Before | 운영 조회를 바꾸기 전에 동일 1만 seed로 DB-only 2라운드와 실제 Redis miss/hit를 측정했다. 기존 raw 증거와 별도로 이번 변경 전 JSON 2개를 보존했다. |
| Red | 새 구조 테스트와 관련 경계 13개 중 6개 실패, 7개 통과. size 1/20/100에서는 count+wide 집계 2회가 새 count/rank/detail 3회 기대에 실패했다. 가격·좋아요/상품 삭제/브랜드 삭제의 3가지 경합은 기존 snapshot 응답 검사 뒤 SELECT 4회 기대에 실제 3회로 실패했다. 컴파일·환경 실패나 인위적으로 기존 결과를 깨뜨린 Red가 아니다. |
| Green | 저장소의 고객 무필터 좋아요순 분기와 내부 읽기 함수만 추가하고 `ProductPageRow`를 재사용했다. 관련 5개 스위트·37개 테스트 모두 통과, 실패·오류·skip 0. |
| 계측 보완 | 비어 있지 않은 DB-only 3회, snapshot 내부 4회, 외부 초기 버전 조회까지 포함한 miss 5회를 정확히 검사한다. 혼합 실험에서 상세 SQL의 `sql.detail` 단계를 분리하여 count/기타 비용에 잘못 포함하지 않는다. 기존 응답·장애·동시성 기대값은 유지한다. |
| 전체 검사 | 14:18 KST 전체 check `BUILD SUCCESSFUL`, 종료 코드 0, 5분 26초. 80개 스위트·733개 통과, 실패·오류·skip 0, Checkstyle XML 11개·위반 0, ArchUnit 1개 통과. 이전 724개에 구조/빈 페이지 4개·좋아요순 경계 파라미터 2개·신규 경합 3개를 추가했다. |

Red 실행:

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.application.product.ProductQueryBaselineTest.ranksUsingOnlyProductIdsAndCountsThenLoadsOnlyTheSelectedDetails' \
  --tests 'com.loopers.application.product.ProductQueryBaselineTest.skipsRankingAndDetailsForOutOfRangeLikesPages' \
  --tests '*ProductLikeRankingSnapshotIntegrationTest' \
  --tests 'com.loopers.application.product.ProductQueryServiceIntegrationTest.preservesGlobalPagesAcrossBrandsWhileExcludingDeletedCandidatesBeforePaging' \
  --tests 'com.loopers.application.product.ProductQueryServiceIntegrationTest.returnsAnEmptyGlobalPageWithoutChangingRowsWhenAllProductsAreHidden' \
  --console=plain
```

Green 실행:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*ProductQueryBaselineTest' \
  --tests '*ProductLikeRankingSnapshotIntegrationTest' \
  --tests '*ProductQueryServiceIntegrationTest' \
  --tests '*ProductListCacheApiIntegrationTest' \
  --tests '*ProductListCacheSnapshotIntegrationTest' \
  --console=plain
```

### 트랜잭션을 보존한 방법

`ProductListReader.readSnapshot()`의 명시적 REPEATABLE_READ 안에서 버전 → count → rank → detail을 실행한다. 테스트는 실제 rank SQL 실행 직후 읽기 스레드를 멈추고, 다른 연결에서 변경과 버전 증가를 커밋한 뒤 상세 조회를 재개한다. 진행 중 결과는 이전 순서·가격·좋아요 수·삭제 전 항목·total을 함께 유지하고 다음 조회는 새 버전·상태를 반영해야 한다. 연결 ID·실제 DB 격리·autocommit·Spring 읽기 트랜잭션·잠금 SELECT 부재도 검사한다.

일반 DB-only `readList()`는 기존 기본 격리의 읽기 트랜잭션을 유지한다. 위 신규 경합 테스트는 명시적 RR인 cache miss 경로의 증거이며, 임의로 외부 READ_COMMITTED 트랜잭션에 합성한 호출까지 보장한다는 뜻은 아니다. 업무 쓰기 서비스·잠금 순서·버전 갱신·Redis 대기 위치는 변경하지 않았다.

### 측정 조건과 한계

- 요청은 브랜드 필터 없는 `GET /api/v1/products?sort=likes_desc&page=0&size=20`이다. seed는 상품 10,000개·노출 9,812개·좋아요 46,955개다.
- DB-only 기준선은 실제 HTTP·수동 DDL·기존 인덱스·10회 준비 후 각 라운드 50표본을 사용한다. 다른 조회 조건과 교차하며 두 라운드를 측정한다. SQL·바인딩·실행 계획과 독립 fixture 기대값의 전체 응답 일치를 보존한다.
- Redis 비교는 같은 fixture에서 DB-only/miss/hit 각각 10회 준비·50표본이다. miss마다 대상 키를 지운 뒤 요청하며 키 삭제 시간은 응답 시간에 포함하지 않는다. 여기서 **첫 miss는 Redis 데이터가 없다는 뜻이지 JVM·DB 버퍼·디스크까지 차가운 상태가 아니다.**
- 전후는 같은 머신의 별도 실행·테스트 컨테이너다. 동일 프로세스에서 무작위로 전환한 A/B나 운영 성능 보장이 아니다. SQL 구조/계획과 HTTP 반복 결과를 함께 판단하며 고정 ms 합격선은 두지 않는다.
- 새 인덱스나 좋아요 카운터 저장은 추가하지 않았다. 전체 후보의 좋아요 집계·정렬과 전체 개수 계산은 여전히 남는다. 줄인 것은 집계에 운반하는 상세 정보와 전체 후보의 상세 읽기 비용이다.

## 전후 실측 결과

### 실제 Redis miss의 HTTP 비용

변경 직전 측정과 변경 후 **최종 전체 검사 안에서 실행한** 동일 측정 테스트를 비교한다. 각 모드 50표본이며 단위는 ms다. 전체 검사 전 관련 Green 실행의 miss p50 25.48ms도 같은 개선 방향이었고, 아래는 마지막 실행의 수치를 사용한다.

| 경로 | SELECT 전 → 후 | p50 전 → 후 | p95 전 → 후 | 해석 |
| --- | --- | --- | --- | --- |
| 캐시 OFF, DB 직접 | 2 → 3 | 56.23 → 24.98 | 61.95 → 41.00 | 추가 상세 조회를 포함하고도 p50 약 55.6% 감소 |
| Redis miss, 원본 계산·채움 | 4 → 5 | 57.57 → 26.61 | 61.66 → 31.14 | p50 약 53.8%, p95 약 49.5% 감소 |
| Redis hit | 1 → 1 | 3.67 → 3.12 | 4.23 → 3.93 | 비싼 조회를 실행하지 않는 경로는 유지. 이 차이를 새 SQL의 hit 개선 효과라고 주장하지 않음 |

원시 표본: [캐시 Before](evidence/product-like-ranking-cache-before-2026-10-16.json) · [캐시 After](evidence/product-like-ranking-cache-after-2026-10-16.json).

### 수동 DDL의 독립 DB-only 비교

별도 기준선 테스트는 캐시를 끄고 8개 목록/상세 조건을 교차 측정한다. 같은 상품 seed·DDL·실제 인덱스·MySQL 버전과 8개 조건의 전체 기대 응답이 전후 같음을 확인했다. 아래는 그중 무필터 좋아요순 결과다. 위 Redis 테스트와 실행 컨텍스트가 다르므로 두 표의 절대 시간을 서로 섞어 비율을 계산하지 않는다.

| 라운드 | HTTP p50 전 → 후 | HTTP p95 전 → 후 | JDBC execute 합 p50 전 → 후 |
| --- | --- | --- | --- |
| 1 | 48.67 → 16.74 | 50.81 → 17.44 | 45.95 → 14.79 |
| 2 | 47.96 → 16.69 | 50.15 → 18.29 | 45.57 → 14.95 |

JDBC execute 합은 이후 ResultSet 소비 등을 제외하므로 완전한 DB 시간은 아니다. [DB Before](evidence/product-like-ranking-before-2026-10-16.json) · [DB After](evidence/product-like-ranking-after-2026-10-16.json)에 SQL·바인딩·계획·응답과 모든 원시 표본을 보존한다. 기준선 JSON의 캐시 미측정 문구는 **해당 DB-only 실험의 범위**를 뜻하며 저장소 전체의 캐시 구현 상태를 뜻하지 않는다.

### 왜 줄었는가: 실행 계획

| 관찰 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 집계 반환 정보 / 그룹 키 | 상세 포함 10개 컬럼 / 9개 필드 | ID·count 2개 컬럼 / ID 1개 |
| 상품 접근 | 순위 계산 중 전체 후보의 상세 필드 필요 | 순위는 기존 `idx_product_brand_deleted`의 covering 접근, 상세는 선정 ID의 PRIMARY range scan |
| 집계 후보·좋아요 lookup | 9,812개·9,812 loops | 동일. 후보 수를 20개로 먼저 줄인 것이 아님 |
| 좋아요 JOIN 출력 | 47,086행 | 동일 |
| 임시 집계·정렬 | 존재 | 여전히 존재. 넓은 상세 정보를 운반하지 않음 |
| 집계·정렬 SQL의 최상위 실제 시간 | 51.8ms | 22.1ms |
| 추가 상세 SQL | 없음 | 20행, 최상위 실제 시간 약 0.031ms |

위 시간은 반복 HTTP 측정 후 얻은 **EXPLAIN ANALYZE 1회**의 보조 증거다. 자식 처리 시간을 포함하는 노드들을 더하지 않으며 디스크 spill 제거를 확인했다고 주장하지 않는다. 좋아요용 `idx_like_product`는 전후 모두 사용한다. 인덱스 추가나 ORM N+1 제거가 아니라, 같은 후보를 더 좁은 데이터로 집계하고 상세 조회 범위를 제한한 개선이다.

### 혼합 부하의 보조 확인

전체 검사에서 기존 DB-only/캐시 단독/요청 합치기의 2라운드·18개 혼합 조건과 6개 burst를 다시 실행했다. 측정 조회 3,600개·좋아요 변경 720개·burst 조회 48개가 응답·SQL·최종 DB 검증을 통과했다. [이번 혼합 원시 증거](evidence/product-like-ranking-mixed-after-2026-10-16.json)를 이전 파일과 별도로 저장했다.

직전 [요청 합치기 실험](evidence/product-list-single-flight-2026-10-16.json)과 비교하면, 초당 좋아요 변경 10회인 COALESCED의 실제 계산 담당 요청 200개 평균은 아래와 같다. 이번 바로 전의 순차 측정과는 다른 **과거 실행과의 보조 비교**이며 운영 성능 보장으로 사용하지 않는다.

| 구간 | 이전 평균 ms | 이번 평균 ms |
| --- | ---: | ---: |
| 집계 SQL execute | 46.83 | 19.48 |
| 추가 상세 SQL execute | 없음 | 0.70 |
| DB snapshot 전체 | 50.14 | 25.26 |
| 해당 요청 HTTP 전체 | 55.34 | 31.63 |

두 실행의 해당 조건에서 집계 횟수는 200회로 같다. 이번에는 중복 계산 횟수보다 **계산 한 번의 비용**을 줄였다는 관찰이다. 평균 구간은 중첩되므로 서로 더하지 않는다. 처리 시간이 짧아져 초기 hit 수 등 실행 일정도 달라질 수 있으므로 모든 부하에서 같은 개선율을 기대하지 않는다.

## 최종 검증과 파일 연결

```bash
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```

- 구현: [ProductQueryRepositoryImpl](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/ProductQueryRepositoryImpl.java), [ProductPageRow](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/ProductPageRow.java).
- 새 구조·페이지 테스트: [ProductQueryBaselineTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductQueryBaselineTest.java), [ProductQueryServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductQueryServiceIntegrationTest.java).
- 순위/상세 사이 커밋 테스트: [ProductLikeRankingSnapshotIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductLikeRankingSnapshotIntegrationTest.java).
- 영향받은 캐시 검사: `ProductListCacheApiIntegrationTest`, `ProductListCacheSnapshotIntegrationTest`, `ProductListCacheMixedLoadIntegrationTest`. SQL 수와 상세 비용 분해만 새 구현에 맞추고 결과·격리·장애 기준은 유지했다.
- [검사 집계·소스/증거 해시](evidence/product-like-ranking-validation-2026-10-16.json)에 이번 작업 트리와 측정 파일을 식별한다. `git diff --check` 통과. 기존 미커밋 Redis 작업과 과거 측정은 보존했으며 커밋·푸시·PR 제출은 하지 않았다.

남는 비용은 전체 후보 집계·정렬, count, 매 hit의 버전 조회와 변경마다 발생하는 버전 갱신이다. 이 SQL 증분 밖이었던 [TTL 다중 주기·메모리 비교](product-list-cache-ttl-memory.md)는 이후 완료했다. 실제 홈 요청 분포는 미확인이고 여러 서버 사이의 중복 계산 제거는 구현하지 않았다.
