# W4 목록·상세 조회 비용 비교

이 문서는 2026-10-15 22:53의 **무필터 목록 추가 개선 전** 관찰을 보존한다. 이후 최신순·가격순은 [페이지 한정 집계 개선](global-list-query-optimization.md), 좋아요순은 [좁은 순위 집계](product-like-ranking-tdd-log.md)를 적용했으므로 아래 값과 SQL 구조를 현재 구현의 값으로 사용하지 않는다.

당시 추천한 좋아요순 첫 페이지의 Redis는 이후 [캐시 TDD](product-list-cache-tdd-log.md)로 구현·검증했다. 후속 [요청 합치기](product-list-single-flight-tdd-log.md)와 [TTL·메모리 측정](product-list-cache-ttl-memory.md)도 별도 기록에 있으며, 현재 전체 상태는 [완료 체크리스트](../week2/commerce-completion-checklist.md)를 따른다. 다른 조회까지 모두 캐시했거나 실제 운영 요청 분포를 검증했다는 뜻은 아니다.

## 결론과 범위

당시 코드의 원본 조회 비용을 기준으로 **전체 상품 좋아요순 첫 페이지를 첫 Redis 실험 대상으로 추천**했다. 상세에 비해 생략할 수 있는 집계·정렬 작업이 컸기 때문이다. 이 관찰 자체가 캐시 구현 승인이나 운영 hit율을 확인한 것은 아니었다. 이 단계에서는 측정 테스트와 기록만 추가했으며 운영 쿼리·인덱스·트랜잭션·락·Redis 동작은 변경하지 않았다.

브랜드 필터 없는 최신순·가격순에도 추가 SQL 개선 여지가 발견됐다. 앞선 [1단계 개선](product-query-optimization.md)에서 확인한 좋아요 20 loops는 **브랜드 1007·가격순 첫 페이지**의 관찰 결과이며, 모든 목록 조건에 적용되는 보장이 아니다.

## 비교 방법

| 항목 | 이번 실행 |
| --- | --- |
| 코드·시간 | 시작 HEAD `374b31e`, 측정 테스트만 확장. 2026-10-15 22:53 KST |
| 환경 | Java 21.0.10 / MySQL 8.0.46 / 전용 Testcontainers / 수동 DDL 001~007 |
| 데이터 | 기존 seed v1 그대로: 상품 10,000개, 고객 노출 9,812개, 브랜드 100개, 좋아요 46,955개 |
| 목록 6조건 | 브랜드 1007 필터 유무 × `latest` / `price_asc` / `likes_desc`. 모두 page=0, size=20 |
| 상세 2조건 | 상품 100010(좋아요 0개), 상품 100997(좋아요 200개). 모두 고객 노출 가능 |
| 준비 | seed·통계 갱신·기존 계약 검사·고정 기준선 실행 후, 각 비교 조건 준비 요청 10회 |
| 반복 | 같은 JVM·DB에서 2라운드, 조건당 라운드별 50회. 총 800회 측정. 순차 요청의 시작 조건을 회전하고 2라운드는 순서를 반대로 함 |
| 정확성 | 매 요청 HTTP 200·SUCCESS 및 전체 data를 seed 공식에서 독립 계산한 값과 비교. 목록은 순서·페이지 정보 포함 |
| 관찰 | 실제 HTTP 시간, 실행 SELECT 수, JDBC execute 시간 합계. 전체 반복 종료 뒤 실제 SQL·바인딩으로 EXPLAIN·EXPLAIN ANALYZE |

목록은 20개 상품과 전체 개수를, 상세는 한 상품을 반환한다. 따라서 같은 기능의 구현 효율 대결이 아니라 **동일 요청이 재사용될 때 피할 수 있는 원본 비용** 비교다. Redis 컨테이너는 기존 애플리케이션 구동용이며 이 조회는 캐시를 사용하지 않는다.

## 실제 응답 시간

단위는 ms. p50은 중앙값, p95는 표본의 95% 지점이며 nearest-rank 방식이다. 각 칸은 **1라운드 / 2라운드**다. SQL 수는 각 조건의 100회 모두 동일했다.

| 요청 조건 | SELECT 수 | HTTP p50 | HTTP p95 | JDBC execute 합계 p50 |
| --- | ---: | ---: | ---: | ---: |
| 브랜드 최신순 | 2 | 4.334 / 4.063 | 9.536 / 7.467 | 1.070 / 0.986 |
| 브랜드 가격순 | 2 | 4.787 / 4.211 | 6.697 / 7.621 | 1.092 / 0.979 |
| 브랜드 좋아요순 | 2 | 5.111 / 6.444 | 7.155 / 9.381 | 1.440 / 1.441 |
| 전체 최신순 | 2 | 21.894 / 21.186 | 24.347 / 23.938 | 18.226 / 17.875 |
| 전체 가격순 | 2 | 21.765 / 22.193 | 24.519 / 25.212 | 18.099 / 17.787 |
| 전체 좋아요순 | 2 | 50.654 / 49.639 | 52.483 / 53.662 | 46.049 / 45.836 |
| 상세 · 좋아요 0개 | 1 | 4.003 / 2.870 | 8.046 / 4.991 | 0.568 / 0.461 |
| 상세 · 좋아요 200개 | 1 | 3.724 / 3.053 | 5.226 / 5.979 | 0.550 / 0.482 |

JDBC execute 시간은 ResultSet 소비가 끝날 때까지의 전체 시간이 아니다. HTTP에는 로컬 통신·서버·JSON 변환·관찰 도구 비용도 들어간다. 작은 비용 차이는 변동에 가려질 수 있으므로 상세의 좋아요 200개가 항상 0개보다 빠르다는 식으로 해석하지 않는다. 고정된 시간 합격 기준은 추가하지 않았다.

## 왜 차이가 났는가

목록의 2개 SELECT는 전체 개수 1회와 본문 1회다. 브랜드 필터의 개수 쿼리는 99개 상품, 전체 개수 쿼리는 노출 상품 9,812개를 센다. 아래는 **본문 SELECT**의 실제 실행 계획이다.

| 조건 | 상품·좋아요 처리 | 정렬·집계 |
| --- | --- | --- |
| 브랜드 가격순 | `idx_product_brand_price`에서 상품 20개, 좋아요 lookup 20 loops | 인덱스 순서로 20개 반환. 별도 sort·전체 임시 집계 없음 |
| 브랜드 최신순 | `idx_product_brand_deleted`에서 상품 99개, 좋아요 lookup 20 loops | 상품 정렬 후 20개 반환 |
| 브랜드 좋아요순 | 상품 99개, 좋아요 lookup 99 loops, left join 결과 459행 | 임시 테이블로 99개 상품 집계·좋아요순 정렬 후 20개 |
| 전체 최신순·가격순 | 브랜드→상품 join 결과 9,812개, 좋아요 상관 조회 **9,812 loops** | projection을 구성한 뒤 정렬하여 20개. 기존 정렬 인덱스가 있어도 이번 계획은 브랜드 기준 접근을 선택 |
| 전체 좋아요순 | 상품 9,812개, 좋아요 lookup **9,812 loops**, left join 결과 **47,086행** | 임시 테이블로 9,812개 상품 집계·좋아요순 정렬 후 20개 |
| 상세 2조건 | PK로 특정 상품·브랜드 확인, 해당 상품의 좋아요 index lookup 1 loop(0행 또는 200행) | 한 상품만 집계. 전체 상품 순위 계산 없음 |

47,086은 전체 좋아요 테이블 행 수가 아니라 삭제 조건과 LEFT JOIN을 적용한 **중간 결과 행 수**다. SQL 내부 loops는 애플리케이션이 요청마다 별도 SELECT를 수천 번 실행하는 N+1과 다르다. 실제 HTTP SELECT 수는 목록 2회, 상세 1회다. 실행 계획은 별도 관찰이며 그 단발 실행 시간을 HTTP p50과 합산하지 않는다. 실행 시간·행 수·반복 수의 의미는 [MySQL EXPLAIN ANALYZE 문서](https://dev.mysql.com/doc/refman/8.0/en/explain.html#explain-analyze)를 참고한다.

## Redis 우선순위 제안 — 당시 미구현

제목의 미구현과 아래 표의 추천·다음 판단은 **2026-10-15 관찰 당시 상태**다. 첫 번째 대상은 이후 캐시로 구현했고 두 번째 대상은 SQL을 개선했다. 현재 결과는 문서 첫머리의 후속 기록을 따른다.

| 순서 | 대상 | 이유·다음 판단 |
| --- | --- | --- |
| 1 | `GET /api/v1/products?sort=likes_desc&page=0&size=20` — 브랜드 필터 없는 좋아요순 첫 페이지 | 이번 실험의 가장 비싼 원본 조회다. 같은 조건이 반복되면 전체 집계·정렬을 생략할 여지가 가장 크므로 첫 cache-aside 실험 대상으로 추천 |
| 2 | 브랜드 필터 없는 최신순·가격순 | 약 21~22ms와 좋아요 9,812 loops를 확인했다. 정렬·페이지 확정 뒤 필요한 상품만 좋아요를 계산하도록 SQL 개선 가능성을 먼저 비교할 가치가 있음. 이번에는 수정하지 않음 |
| 후순위 | 브랜드 필터 목록·상세 | 현재 측정 비용이 상대적으로 낮다. Redis가 불필요하다는 뜻은 아니며, 실제 반복 요청 빈도와 변경 빈도가 높거나 낮은지에 따라 우선순위가 달라질 수 있음 |

첫 행은 당시 **우선 검증할 요청**을 정한 제안이었다. 모든 정렬·페이지를 자동으로 캐시하거나 상세까지 함께 캐시한다는 결정은 아니며, 현재도 캐시 범위는 선택한 좋아요순 첫 페이지에 한정된다. 향후 범위를 확장하면 brandId·sort·page·size를 구분해야 한다.

캐시를 선택할 때 비싼 조회인지뿐 아니라 같은 키가 얼마나 반복되는지와 얼마나 자주 무효화되는지도 중요하다. hit가 드물면 캐시 확인·채움 비용이 이익보다 클 수 있다는 점은 [Cache-Aside 지침](https://learn.microsoft.com/en-us/azure/architecture/patterns/cache-aside)과도 일치한다. 이번 균등 요청 실험으로 실제 사용자의 hit율을 추정하지 않는다.

### 목록 캐시의 추가 부담

- 전체 좋아요순은 **현재 페이지 밖 상품의 좋아요 변경**도 순위를 바꿀 수 있다. 캐시 안에 있는 20개만 추적하면 부족하다.
- 현재 응답은 가격·재고·브랜드 이름·좋아요 수를 포함한다. 상품 생성·수정·삭제, 브랜드 이름 변경·일괄 삭제, 좋아요 등록·취소, 관리자 재고 변경·주문 확정이 영향을 준다.
- 삭제 상품만 캐시 목록에서 빼서 19개를 반환하면 다음 상품·순서·total이 맞지 않을 수 있다. 삭제 비노출과 정확한 페이지 계약을 함께 지켜야 한다.
- [W4 기준](grading-criteria.md)에 따라 정상 변경 완료 후 새 순차 조회는 변경 결과를 반환해야 한다. 좋아요를 일정 시간마다만 갱신하도록 정책을 임의 변경하지 않는다.
- 당시 payload·키·TTL, 늦은 채움/무효화 실패 대응과 복구 기준은 별도 합의 대상이었다. 이후 [캐시 TDD 기록](product-list-cache-tdd-log.md)에 선택한 정책과 DB 확인 비용을 포함한 DB-only·miss·hit 검증을 남겼다.

따라서 **당시 추천은 전체 좋아요순 목록부터 이득을 검증하자는 것**이었다. 이 DB-only 관찰만으로 Redis 적용 후의 지연이나 개선율을 주장하지 않는다. 후속 캐시 실험과 좋아요순 SQL 개선의 수치는 각각의 실행 기록에서 확인하며, 아래 과거 표에 섞지 않는다.

## 재현·검증

[측정 테스트](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductQueryBaselineTest.java)의 기본 모드에서 기존 고정 기준선과 이번 8조건 비교를 함께 실행한다. `W4_COMPARE_BRAND_PRICE_INDEX`를 설정하지 않는다. 인덱스 비교 모드는 기존 동작을 유지하며 이번 목록·상세 비교를 실행하지 않는다.

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.application.product.ProductQueryBaselineTest' \
  --tests 'com.loopers.support.JdbcReadProbeTest' \
  --tests 'com.loopers.application.product.ProductQueryServiceIntegrationTest' \
  --tests 'com.loopers.interfaces.api.product.ProductQueryApiE2ETest' \
  --tests 'com.loopers.interfaces.api.product.ProductBoundaryApiE2ETest' \
  --tests 'com.loopers.interfaces.api.product.ProductApiE2ETest' \
  --tests 'com.loopers.architecture.ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  --rerun-tasks --console=plain
```

- 2026-10-15 22:53 KST: **7개 스위트·58개 테스트 통과, 실패·오류·skip 0**, ArchUnit 1개 포함. Checkstyle main/test 2개 보고서 위반 0. `BUILD SUCCESSFUL`, 종료 코드 0.
- 측정 테스트 자체는 14.329초에 완료했다. 800개 요청은 그 테스트 내부의 측정 표본이며 800개 JUnit 테스트를 추가한 것이 아니다.
- 기존 구현의 비용 관찰이므로 인위적인 Red나 운영 코드 변경을 만들지 않았다. 전체 프로젝트 `check` 재실행이나 W4 캐시 과제 완료를 뜻하지 않는다.
- 생성 파일: `apps/commerce-api/build/reports/week4/product-read-cost-comparison.json`. 당시 파일은 [원시 증거](evidence/product-read-cost-comparison-2026-10-15.json)에 검증 명령·결과와 함께 보존한다. 원시 표본·전체 기대/검증 data·SQL·바인딩·계획·실제 인덱스·소스/seed/test SHA-256을 포함한다.

이 기록은 동일 JVM·DB의 준비된 순차 읽기 실험이다. 이 실험에서는 독립 실행 2회, cold disk, 동시 요청 처리량, 동시 쓰기, 실제 사용자 요청 분포, Redis 장애·hit·miss·무효화 비용을 검증하지 않았다. 후속 캐시·혼합 부하 실험이 추가됐어도 이 표를 운영 성능이나 캐시 향상률로 일반화하지 않는다.
