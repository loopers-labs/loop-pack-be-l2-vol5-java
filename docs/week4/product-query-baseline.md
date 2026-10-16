# W4 상품 조회 기준선 측정

기존 상품 목록의 응답과 DB 조회 비용을 먼저 기록한 뒤, 같은 조건에서 SQL·인덱스 개선 효과를 비교하기 위해 준비한 최초 기준선 기록이다. 당시 이 증분은 측정 기반만 마련했으며 성능 개선이나 Redis 캐시 완료를 뜻하지 않았다. 요구사항은 [W4 원문](assignment-original.md), 유지할 계약은 [API 계약](../week2/commerce-api-contract.md#목록-필터페이지정렬-제안)을 따른다.

이 문서의 개선 전 수치는 당시 기록으로 보존한다. 후속 SQL·인덱스 변경과 559개 전체 검사 결과는 [1단계 개선 기록](product-query-optimization.md)에 있다. 현재 측정 테스트의 기본 실행은 001~007을 적용한 **현재 구현**이며 `build/reports/week4/product-query-current.json`에 기록한다. 아래 개선 전 파일 이름·6개 DDL 설명은 당시 실행 기준이다. 이전 쿼리와 현행 쿼리를 label만으로 바꾸지는 않는다.

후속 [목록·상세 비용 비교](product-read-cost-comparison.md)도 기본 실행에 포함된다. 목록 6조건·상세 2조건을 별도 준비·2라운드로 측정하고 `build/reports/week4/product-read-cost-comparison.json`에 기록한다. 이 문서의 고정 요청 5회 준비·20회 측정과는 별도 구간이다. 현재도 이 측정은 캐시를 끄고 실행한다. 이후 구현·검증한 Redis는 [캐시 TDD 기록](product-list-cache-tdd-log.md), 현재 W4 상태는 [전체 완료 체크리스트](../week2/commerce-completion-checklist.md)에서 확인한다.

## 진행 범위

1. 기존 수동 DDL을 전용 MySQL 컨테이너에 적용한다. 로컬·공유 DB와 기존 자동 생성 테스트 스키마를 변경하지 않는다.
2. 재현 가능한 상품 10,000개 seed와 독립적인 기대값으로 실제 HTTP 응답을 검증한다.
3. 요청 구간의 실제 JDBC SELECT와 바인딩을 수집하고 같은 SQL의 EXPLAIN·EXPLAIN ANALYZE를 기록한다.
4. 준비 실행 5회 후 순차 요청 20회의 HTTP 지연과 응답을 기록한다. 임의의 밀리초 합격 기준이나 SQL 수 목표는 두지 않는다.
5. 기준선이 확보되면 쿼리·인덱스 후보를 하나씩 비교한다. 이번 증분에서는 생산 코드와 인덱스를 바꾸지 않는다.

기준선 작성 당시에는 캐시 대상·TTL·오래된 표시 허용 범위·커밋 후 무효화 실패 응답·복구 완료 기준이 미정이어서, 답변을 기다리는 동안 DB 기준선 작업을 독립적으로 진행했다. 이후 선택한 정책과 검증 결과는 위의 캐시 TDD 기록을 따른다.

## 재현 데이터

소스는 [product-query-baseline.sql](../../apps/commerce-api/src/test/resources/fixtures/week4/product-query-baseline.sql)이다. 비어 있는 전용 DB에 한 번 적용하는 fixture이며 운영 migration이나 기존 데이터 초기화 스크립트가 아니다. 재실행은 소유한 새 컨테이너에서 수행한다. 기존 식별 사용자 1·2·3은 변경하지 않는다.

| 구분 | seed v1의 기대값 |
| --- | --- |
| 브랜드 | 100개, ID 1001~1100 |
| 상품 | 10,000개, ID 100001~110000 |
| 좋아요용 사용자 | 200개, ID 10001~10200 |
| 좋아요 관계 | 46,955개, 사용자·상품 중복 없음 |
| 고객 노출 상품 | 9,812개 |
| 삭제 상품 | 188개. 브랜드 1100의 상품 전체와 별도 상품 삭제 포함 |
| 좋아요 0개 상품 | 999개 |
| 좋아요 200개 인기 상품 | 10개 |
| 품절 상품 | 588개. 품절만으로 고객 조회에서 제외하지 않음 |
| 전체 상품 가격 합계 | 509,500,000 |

상품 순번을 `i=1..10000`으로 두고 브랜드는 `1001+(i-1)%100`, 가격은 `1000+((i*37)%1000)*100`으로 만든다. 고정 브랜드 안에도 가격 동점이 생긴다. 좋아요 수는 `i%997==0`이면 200, 나머지는 `((i-1)/100의 정수 몫+i)%10`이다. 삭제·재고·이름·시각까지 SQL에 고정한다.

## 고정 요청과 정확성

```http
GET /api/v1/products?brandId=1007&sort=price_asc&page=0&size=20
```

HTTP 200·`meta.result=SUCCESS`, `totalElements=99`, `totalPages=5`를 기대한다. 상품의 여섯 필드와 페이지 정보를 seed 공식에서 독립적으로 계산한 값과 비교한다. 가격 동점은 ID 내림차순이다.

```text
109407,108407,107407,106407,105407,103407,102407,101407,100407,
109707,108707,107707,106707,105707,104707,103707,102707,101707,100707,
109007
```

첫 9개는 가격 6900·좋아요 1, 다음 10개는 가격 16900·좋아요 4, 마지막은 가격 26900·좋아요 7이다. 품절 상품 109707은 포함하고 삭제 상품 104407은 제외한다. 위 값은 seed에서 계산한 테스트 기대값이며 실제 실행 기록과 구분한다.

## 측정 방법과 해석

- 실제 CommerceApiApplication을 RANDOM_PORT로 실행한다. 예제 엔티티의 테이블을 임의로 추가하지 않고 이 측정에서는 Hibernate 자동 DDL을 끈다. 커머스 6개 수동 DDL을 적용하고 실제 인덱스를 확인한다.
- seed 적재 뒤 전용 DB에서 `ANALYZE TABLE brand, product, like, user`로 통계를 갱신하고 성공을 검사한다. 개선 후 측정도 같은 준비를 한다. 빈 테이블의 통계가 남은 예비 실행을 인덱스 개선의 기준선으로 삼지 않는다.
- 기존 testFixtures의 자동 컨테이너 설정은 이 테스트에서만 제외하여 다른 DB·Redis를 시작하거나 전역 연결 속성을 바꾸지 않는다. Redis 연결이 준비되어도 이번 상품 조회는 캐시를 사용하지 않는다.
- 테스트 요청은 순차 실행한다. JDBC 관찰은 `start → HTTP 응답 → stop` 구간만 활성화한다. seed·인덱스 조회·EXPLAIN은 요청 SQL 수에 포함하지 않는다. 이 도구는 병렬 부하의 요청별 추적기가 아니다.
- SQL과 바인딩을 분리해 보관하고 같은 prepared SQL에 바인딩을 전달해 실행 계획을 얻는다. SQL을 축약하거나 조건을 뺀 대체 쿼리를 사용하지 않는다.
- 관찰 도구는 현재 조회의 SELECT와 숫자 바인딩을 대상으로 한다. 모든 JDBC 명령·CTE·LOB의 범용 추적/재생 도구가 아니며 성공한 SELECT 실행만 센다.
- HTTP 시간은 클라이언트 요청부터 응답 수신까지이며 JVM·HTTP·JSON·DB 비용을 포함한다. JDBC `executeNanos`는 execute 호출 반환까지로, 전체 결과 fetch나 HTTP 시간과 같지 않다.
- 최소값·p50·p95·최대값과 원시 표본을 함께 보관한다. 로컬 컨테이너, 작은 표본, 계측·테스트 실행의 비용을 포함하므로 운영 처리량이나 지연 상한으로 일반화하지 않는다. 개선 후도 같은 측정 조건을 유지한다.
- 실행 계획의 EXPLAIN ANALYZE 역시 SELECT를 실제 실행한다. 준비 실행·표본 측정과 구간을 구분하며, 사용 key·예상/실제 rows·loops·정렬 단계를 읽는다.

## 실행 (현재 측정 도구)

Docker와 Java 21이 필요하다. 테스트가 소유한 컨테이너만 종료하며 공유 Redis FLUSHALL이나 기존 DB 삭제를 하지 않는다.

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.support.JdbcReadProbeTest' \
  --tests 'com.loopers.application.product.ProductQueryBaselineTest' \
  --rerun-tasks --console=plain
```

현재 기본 실행은 `apps/commerce-api/build/reports/week4/product-query-current.json`에 생성한다. 개선 전 당시 출력은 `product-query-baseline.json`이었으며 아래 evidence 파일로 보존했다. 지금의 쿼리·001~007 스키마로 재실행한 결과를 개선 전 기준선으로 덮어쓰지 않는다. 인덱스 비교 모드와 파일 표식은 [후속 재현 절차](product-query-optimization.md#재현)를 따른다. 빌드 산출물은 재실행 시 갱신되므로 각 실행의 실제 코드·SQL·스키마·환경을 구분해 보관하고, 통과하지 않은 실행을 성능 증거로 사용하지 않는다.

## 개선 전 실제 측정 결과

2026-10-15 15:18 KST에 통계 갱신을 포함한 기준선을 실행했다. [원시 측정 결과](evidence/product-query-baseline-2026-10-15.json)에 실제 SQL·바인딩, EXPLAIN·EXPLAIN ANALYZE, 전체 응답, 원시 HTTP 표본, 실제 인덱스를 보존했다.

| 항목 | 관찰 결과 |
| --- | --- |
| 생산 코드 기준 | HEAD `63af323`, 운영 쿼리·인덱스 변경 없음 |
| 실행 환경 | Java 21.0.10, MySQL 8.0.46, 전용 로컬 컨테이너, 직렬 HTTP 요청 |
| 준비와 표본 | 수동 DDL·seed·ANALYZE TABLE·계약 검사 후 준비 실행 5회, 측정 20회 |
| 응답 | 200·SUCCESS, 20개 품목과 모든 응답 필드 일치, totalElements 99·totalPages 5 |
| 성공 SELECT 수 | 측정 20회 모두 요청당 2회. 전체 개수 1회와 목록 본문 1회 |
| HTTP 최소 / p50 / p95 / 최대 | 6.502 / 7.646 / 9.369 / 10.562 ms. nearest-rank 백분위 |
| 전체 개수 쿼리 | `idx_product_brand_deleted`, 실제 99행, covering index lookup |
| 목록 본문 쿼리 | 상품 `idx_product_brand_deleted`로 99행, 좋아요 `idx_like_product` lookup 99 loops, 조인 결과 459행 |
| 목록 집계와 정렬 | 임시 테이블로 99개 상품 집계 후 price ASC·id DESC 정렬, 20개 반환 |

SQL이 2번이라는 사실과 DB 내부 조인의 99 loops는 다르다. 이 실행은 상품마다 애플리케이션이 좋아요 SQL을 추가 실행하는 N+1이 아니다. 당시에는 이 결과만으로 특정 대안을 채택하지 않고, 집계 범위를 줄이는 쿼리와 필터·가격 정렬에 맞는 인덱스를 다음 비교 대상으로 삼았다. 후속 선택과 실측은 [1단계 개선 기록](product-query-optimization.md)에 보존한다.

통계 갱신 전 예비 실행에서는 예상 1행과 실제 9,812행의 차이가 있었고 `idx_product_latest`가 선택됐다. 이를 수정한 측정 준비를 최종 기준선으로 삼는다. 그 전후 시간 차이를 쿼리·인덱스 개선 성과로 계산하지 않는다.

재현 파일 SHA-256:

```text
seed v1: 912f7bbcfcce8ce19e47ead734a658a6ace998999c495cacf20fac65f0106197
ProductQueryRepositoryImpl.java: 15c250d8f88881f93e920eff255d30c286e79c851d415d5ce8438a2822250ebc
```

## 검증 기록

- 2026-10-15 SQL 관찰 도구 Red: 비어 있는 구현에서 단위 테스트 5개 중 4개가 기대대로 실패했다. SQL·바인딩 수집과 중첩 측정 거절이 구현되지 않은 실패다.
- 같은 날 Green: 구현 후 단위 테스트 5개와 Checkstyle test가 통과했다. DB 없이 관찰 도구를 검증한 결과이며 실제 API·MySQL 성능 측정을 대신하지 않는다.
- 실제 MySQL·HTTP 기준선은 첫 실행부터 응답 검증을 통과했다. 이후 통계 갱신 준비와 분포·고정 ID 검증을 보강하고 재실행했다.
- 2026-10-15 15:18 KST 최종 관련 검사: **7개 스위트·55개 테스트, 실패·오류·skip 0**, 종료 코드 0. 신규 기준선 1개·관찰 도구 5개, 기존 상품 서비스 7개·상품 API 18개·입력 경계 20개·조회 API 3개·ArchUnit 1개를 포함한다. 전체 프로젝트 `check` 실행이나 W4 캐시 완료를 뜻하지 않는다.
- Checkstyle main은 생산 코드 미변경으로 UP-TO-DATE, test는 재실행했으며 두 보고서의 위반은 0개다. `git diff --check`도 통과했다.

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.support.JdbcReadProbeTest' \
  --tests 'com.loopers.application.product.ProductQueryBaselineTest' \
  --tests 'com.loopers.application.product.ProductQueryServiceIntegrationTest' \
  --tests 'com.loopers.interfaces.api.product.ProductQueryApiE2ETest' \
  --tests 'com.loopers.interfaces.api.product.ProductBoundaryApiE2ETest' \
  --tests 'com.loopers.interfaces.api.product.ProductApiE2ETest' \
  --tests 'com.loopers.architecture.ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```
