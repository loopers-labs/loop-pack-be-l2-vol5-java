# W4 전체 최신순·가격순 목록의 페이지 한정 좋아요 집계

## 결과와 변경 범위

2026-10-15, 시작 HEAD `c161dea`의 작업 트리에서 고객 **브랜드 필터 없는 최신순·가격순**만 개선했다. 같은 10,000개 데이터의 실제 HTTP 첫 페이지에서 중앙값이 약 21~22ms → 11ms로 줄었다. 두 라운드의 감소율은 최신순 48.3~49.6%, 가격순 48.9~49.0%다.

이 단계에서는 Redis·로컬 캐시·신규 인덱스를 추가하지 않았다. 브랜드 필터 목록, 좋아요순, 상세, 관리자, 본인 좋아요는 당시 기존 조회 경로를 유지했다. API 필드·정렬·동점 기준·페이지·전체 개수·삭제 비노출은 바꾸지 않았다.

아래 수치·검사 건수는 당시 기록이다. 이후 무필터 좋아요순은 [좁은 순위 집계](product-like-ranking-tdd-log.md)로 개선했고, 그 첫 페이지의 [Redis 구현·장애 검증](product-list-cache-tdd-log.md), [요청 합치기](product-list-single-flight-tdd-log.md), [TTL·메모리 측정](product-list-cache-ttl-memory.md)도 진행했다. 현재 전체 상태는 [완료 체크리스트](../week2/commerce-completion-checklist.md)를 따르며, 이 최신순·가격순 SQL 개선을 해당 경로의 캐시 구현으로 해석하지 않는다.

## 무엇을 바꾸었는가

| 단계 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 전체 개수 | 노출 가능한 상품 개수 조회 | 동일 |
| 페이지 선택 | 상품·브랜드와 좋아요 상관 count를 한 SQL에서 조회. 측정 계획에서는 9,812개 상품의 좋아요를 계산한 뒤 정렬 | 좋아요 없는 상품·브랜드 DTO를 정렬하여 페이지를 먼저 선택 |
| 좋아요 | 페이지 선택 SQL 내부의 상관 count | 선택된 상품 ID만 `IN (...) GROUP BY productId`로 한 번에 집계 |
| 응답 구성 | 조회 결과를 그대로 변환 | 처음 선택한 페이지 순서로 합성. 집계 결과가 없는 상품은 좋아요 0 |
| 비어 있는 페이지 | 전체 개수 확인 후 빈 결과 | 유지. 범위 밖이면 페이지·좋아요 SELECT 생략 |
| 비어 있지 않은 요청의 SELECT | 2회 | 3회. 상품 수에 비례해 늘어나는 개별 SELECT는 없음 |

[ProductQueryRepositoryImpl](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/ProductQueryRepositoryImpl.java)이 이 순서를 책임진다. 새 [ProductPageRow](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/ProductPageRow.java)는 infrastructure 내부의 중간 DTO이며 domain 계약이나 HTTP 응답을 추가하지 않는다.

**SQL 개수는 늘었지만 SQL 안에서 반복하던 불필요한 좋아요 계산을 줄였다.** 이전 9,812 loops는 애플리케이션이 SELECT를 9,812번 보내는 JPA N+1이 아니라 한 SQL 안의 실행 계획 반복이었다.

### 선택 이유와 남긴 대안

| 선택 | 이유 | 이번에 채택하지 않은 대안 |
| --- | --- | --- |
| JPQL의 페이지 조회와 좋아요 일괄 집계를 분리 | 기존 저장소·DTO 구조로 집계 대상을 명확하게 제한하고 바인딩을 검증할 수 있음. 추가 왕복을 포함한 HTTP 시간도 개선됨 | native SQL의 derived table에서 페이지를 먼저 선택하고 좋아요를 결합하면 왕복을 줄일 수 있으나 SQL·매핑·계획 관리가 복잡해짐. 왕복 비용이 병목인 환경에서 후속 비교 가능 |
| 기존 `idx_like_product` 재사용 | 선택 ID들의 좋아요를 covering index range scan으로 읽는 실제 계획 확인 | 같은 목적의 새 인덱스 추가. 현재 이득 근거가 없고 저장·쓰기 비용이 늘어남 |

전체 개수와 상품 페이지는 **여전히 9,812개 후보를 읽고, 페이지 SQL은 정렬도 수행한다.** “DB가 상품 20개만 읽는다”거나 “모든 정렬 비용을 제거했다”는 개선이 아니다.

## 트랜잭션과 동시 변경

서비스·저장소의 기존 읽기 트랜잭션을 유지했으며 비관적 읽기 락이나 쓰기 경로의 락·격리 설정은 바꾸지 않았다. 세 데이터 SELECT가 한 트랜잭션·연결에서 실행되는지 검사했다.

`readOnly=true` 자체가 같은 시점의 데이터를 보장하는 것은 아니다. 현재 실제 MySQL 읽기 트랜잭션은 **REPEATABLE READ**이며 이 조건에서 일관된 스냅샷을 검증했다. 읽기 격리를 READ COMMITTED 등으로 바꾸면 이 전제와 테스트를 다시 검토해야 한다.

실제 상품 페이지 SELECT 직후 조회를 잠시 멈추고, 다른 연결에서 가격 100→500과 좋아요 0→1을 함께 커밋했다. 진행 중 요청은 100·0, 커밋 이후 시작한 다음 요청은 500·1을 반환했다. 가격 100·좋아요 1처럼 시점이 섞이지 않음을 확인한 것이다. 당시 미정이던 삭제와 겹친 조회의 정책까지 새로 정한 테스트는 아니며, 후속 정책과 삭제 경합 검증은 위의 캐시·좋아요순 기록을 따른다.

## TDD와 회귀 검증

| 구분 | 실제 확인 내용 |
| --- | --- |
| 결과 보존 | 무필터·여러 브랜드·동점 상품 25개로 20/5/빈 페이지, ID 내림차순, 전체 DTO, 좋아요 0, 품절, 삭제 상품·삭제 브랜드 제외, total 검증. 조회 전후 저장 상태도 비교 |
| 노출 상품 0개 | 상품·좋아요 행은 남아 있지만 브랜드가 삭제된 상태에서 두 정렬 모두 빈 items·total 0·totalPages 0, 저장 상태 유지 |
| 구조적 Red | 운영 코드 변경 전 19개 중 최신순·가격순 구조 검사 2개 실패. 실제 SELECT 2개와 페이지 SQL 내부 좋아요 상관 집계가 새 경계를 만족하지 못함. 결과 보존·기존 스냅샷 사례는 이미 통과 |
| Green | 페이지 분리·일괄 집계 구현 후 관련 25개와 main/test Checkstyle 통과. 이후 노출 0개 2사례와 정확히 3개 데이터 SELECT의 스냅샷 검증을 보강 |
| SQL 범위 | 실제 HTTP에서 size 1/20/100 × 첫·다음 페이지. SELECT 3개 고정, 페이지 SQL에 좋아요 없음, 집계 바인딩 ID와 반환 페이지 ID가 정확히 일치 |
| 범위 밖 | page 491·Integer.MAX_VALUE에서 정확한 전체 개수와 빈 결과, count SELECT 1회만 실행 |
| 실제 스냅샷 | 두 정렬에서 실제 서비스 프록시·RR·동일 연결·autocommit false·읽기 트랜잭션, 다른 연결의 커밋, 현재·다음 응답 검증. 잠금 SELECT 없음 |
| 대기·정리 | SQL·래치·future에 유한 timeout 적용, reader 종료 확인 후 DB 정리 |

처음 여러 테스트를 실행할 때 Mockito의 테스트별 spy 초기화 때문에 SQL 관찰이 0개로 나오는 측정 도구 문제가 있었다. 관찰 설치를 `@BeforeAll`에서 `@BeforeEach`로 옮긴 뒤 **의도한 제품 구조 Red를 다시 확인**했다. 도구 실패를 기능 Red로 계산하지 않았다. 세 SELECT는 이번에 선택한 구조의 회귀 기준이며 과제가 지정한 유일한 정답이 아니다. 고정 ms 합격선은 두지 않았다.

스냅샷 테스트의 “3개”는 업무 데이터 SELECT 수다. 테스트가 직접 실행한 연결·격리 수준 확인용 SELECT는 이 수에 포함하지 않는다.

주요 테스트:

- [ProductQueryBaselineTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductQueryBaselineTest.java): 실제 HTTP·SQL 경계·반복 성능.
- [ProductQueryServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductQueryServiceIntegrationTest.java): 무필터 페이지·빈 결과·기존 조회 회귀.
- [ProductPageSnapshotIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductPageSnapshotIntegrationTest.java): 중간 커밋이 발생하는 실제 읽기 트랜잭션.

## 실제 전후 성능

변경 전·후 각각 **성공한 동일 단독 측정 명령**의 보고서를 비교했다. 중간 Red 실행이나 전체 검사 중 생성된 측정값을 섞지 않았다.

| 조건 | 값 |
| --- | --- |
| 시각 | 변경 전 2026-10-15 23:23:05 KST / 변경 후 23:26:19 KST |
| 환경 | Java 21.0.10, MySQL 8.0.46, 격리된 Testcontainers, 수동 DDL 001~007 |
| 동일 데이터 | 상품 10,000개, 노출 9,812개, 브랜드 100개, 좋아요 46,955개 |
| 비교 요청 | `GET /api/v1/products?sort=latest&page=0&size=20`, `sort=price_asc` |
| 반복 방식 | 기존 8조건 비교 유지. 조건별 준비 10회, 라운드당 50회 × 2라운드. 시작 조건 회전, 2라운드는 역순 |
| 정확성 | 모든 요청의 HTTP 200·SUCCESS·전체 data를 독립 기대값과 비교. 전후 8조건 기대/검증 data 동일 |
| 비교 동등성 | seed·측정 테스트 SHA-256, DDL 목록·실제 인덱스 동일. 저장소 구현 SHA-256은 변경 |
| 원시 증거 | [변경 전](evidence/global-list-before-2026-10-15.json), [변경 후](evidence/global-list-after-2026-10-15.json): 원시 HTTP/JDBC 표본, SQL·바인딩·계획·검증 data·환경·해시 |

아래는 HTTP 응답 시간(ms)이다. p50은 중앙값, p95는 nearest-rank 방식의 95% 지점이며 감소율은 `(변경 전 - 변경 후) / 변경 전`이다.

| 정렬 | 라운드 | p50 전 → 후 | p50 감소 | p95 전 → 후 | p95 감소 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 전체 최신순 | 1 | 21.806 → 11.266 | 48.3% | 23.923 → 13.676 | 42.8% |
| 전체 최신순 | 2 | 21.082 → 10.622 | 49.6% | 22.996 → 13.477 | 41.4% |
| 전체 가격순 | 1 | 21.688 → 11.065 | 49.0% | 22.938 → 12.882 | 43.8% |
| 전체 가격순 | 2 | 21.119 → 10.798 | 48.9% | 26.024 → 15.495 | 40.5% |

다른 6조건은 코드 경로를 유지했다. 전후 SELECT 수·응답 동일을 확인했지만, 그 조건들의 작은 시간 변화를 이번 개선의 효과로 주장하지 않는다.

### 실행 계획에서 확인한 작업량

| 항목 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 전체 개수 | 노출 상품 9,812개 | 동일 |
| 상품 페이지 | 9,812개 후보와 좋아요 상관 조회 9,812 loops 후 정렬·20개 반환 | 좋아요 없는 9,812개 후보 정렬·20개 반환 |
| 최신순 좋아요 | 페이지 선정 전 전체 후보에서 계산 | 선택 ID 20개의 좋아요 150행을 기존 인덱스로 range scan, 집계 SQL 1회 |
| 가격순 좋아요 | 페이지 선정 전 전체 후보에서 계산 | 선택 ID 20개의 좋아요 268행을 기존 인덱스로 range scan, 집계 SQL 1회 |
| HTTP당 SELECT | 2 | 3 |

변경 후 좋아요 실행 계획은 **range scan 1 loop**다. “9,812 loops가 20 loops가 되었다”는 설명이 아니라, 상관 조회를 없애고 20개 ID의 범위를 한 번 집계한 것이다. EXPLAIN ANALYZE는 반복 측정이 끝난 뒤 별도로 실행했으며 단발 실행 시간이나 부모·자식 노드 시간을 HTTP 지연과 합산하지 않았다.

### 측정 한계

로컬의 준비된 순차 읽기 실험이며 변경 전·후 각각 한 프로세스 실행 안의 두 라운드다. 두 번의 독립 반복 실험이나 운영 부하 검증으로 확대 해석하지 않는다. 데이터·측정 코드는 같지만 JVM·DB 프로세스는 전후 별도이므로 JIT·실행 환경의 변동은 남는다. HTTP 시간에는 통신·서버·JSON·관찰 비용이 포함되며 JDBC execute 합계는 ResultSet 전체 소비 시간이 아니다.

이 DB-only 실험에서는 동시 요청 처리량, cold disk, 큰 offset의 성능, 운영 데이터 분포, Redis hit/miss·장애·무효화 비용을 측정하지 않았다. 추가 왕복의 비용이 큰 운영 환경에서는 이득이 달라질 수 있다. 후속 캐시 검증과 통제된 혼합 부하 측정은 별도 기록으로 구분하며, 이 과거 측정만으로 현재 W4 전체 완료나 운영 성능을 판정하지 않는다.

## 재현

변경 전·후의 단독 측정 명령:

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.application.product.ProductQueryBaselineTest.measuresExistingHttpQueryAgainstManualIndexesAndDeterministicTenThousandProducts' \
  --console=plain
```

두 실행 모두 1개 테스트 통과·BUILD SUCCESSFUL·종료 코드 0이다. 현재 코드로 재실행하면 변경 후 구현을 측정하며 변경 전 값이 재생성되는 것은 아니다. 기존 `W4_COMPARE_BRAND_PRICE_INDEX` 비교 모드는 사용하지 않는다.

전체 검사 명령:

```bash
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```

2026-10-15 23:31 KST에 **BUILD SUCCESSFUL·종료 코드 0**, 1분 38초로 완료했다.

| 최종 검사 | 결과 |
| --- | --- |
| commerce-api 전체 테스트 | XML 63개 스위트·569개 통과, 실패·오류·skip 모두 0 |
| 이번 주요 테스트 | 기준선/SQL 5개·조회 서비스 14개·스냅샷 2개·JDBC 관찰 도구 5개. 전체 569개에 포함 |
| ArchUnit | 1개 통과. 전체 569개에 포함 |
| Checkstyle | main/test/testFixtures XML 11개, 위반 0. 기존 검사 규칙 유지 |
| 기존 회귀 | 상품·브랜드·좋아요·주문·포인트·권한·스키마·동시성 테스트 포함 |

[실행 검증 기록](evidence/global-list-validation-2026-10-15.json)에 Red 실패 메시지, Green·전체 검사 명령과 집계, 테스트 스위트별 결과·변경 소스 해시·전후 비교 조건 확인을 보존한다. 생성되는 로컬 보고서는 재실행 시 덮어써지므로 전후 측정 원본은 별도 증거 파일로 유지한다.

