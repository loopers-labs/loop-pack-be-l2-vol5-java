# R01. 전체 최신순·가격순 인덱스와 전후 측정

[전체 요구사항](../total_requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [계획](plan.md) · [JMeter 안내](jmeter-guide.md)

작업 브랜치: `volume-4/r01-product-list-index` · PR 대상: `volume-4/main`

상태: 2026-10-16 SQL seed 실행기·상품 100만 개 적재와 검증을 완료했다. [실제 결과](result.md)를 참고한다.
세 조회의 실제 HTTP SQL·응답을 수집하고 JMeter 각 1건 확인을 통과했다.
첫 CLI 본 측정은 최신순 10명에서 timeout 10건으로 중단됐다. 부분 결과를 보존하고 [GUI 안내](jmeter-ui-guide.md)를 추가한다.
기존 4개를 유지하고 새 2개를 전용 DB에 적용했다. 전후 실행 계획과 세 API 전체 응답 동일성을 확인했다.
사용자 위임으로 변경 후 CLI 12개를 완료했다. 본 측정 65,261건·오류 0건이며 [결과](baseline/jmeter-after/README.md)에 반복별 지표를 보존했다.
기준 커밋은 `72ae005d40bc5689618898340c488a19aee70993`이다. 현재 브랜치는 `volume-4/main`에서 분기했다.

## 1. 목적과 이번 단계

상품 목록의 전체 최신순·전체 가격순에 맞는 인덱스를 추가하고 같은 데이터에서 전후 비용과 결과를 비교한다.
브랜드별 가격순은 과제 필수 측정 경로이므로 함께 비교한다.

현재 요청은 **실패 기록 보완, 전후 실행 계획 수집, 기존 4개 유지와 새 2개 적용 및 회귀 검증**이다.
변경 전 JMeter는 부분 기준선으로 유지한다. 변경 후 부하 측정은 이후 사용자 위임으로 Codex가 수행했다.

## 2. 확정한 인덱스

| 조회 | 인덱스 이름 | 열 순서 | 결정 |
|---|---|---|---|
| 전체 최신순 | `idx_products_deleted_created` | `deleted, created_at DESC, id DESC` | 적용 완료 |
| 브랜드별 최신순 | `idx_products_deleted_brand_created` | `deleted, brand_id, created_at DESC, id DESC` | 기존 유지 |
| 전체 가격순 | `idx_products_deleted_price` | `deleted, price ASC, id DESC` | 적용 완료 |
| 브랜드별 가격순 | `idx_products_deleted_brand_price` | `deleted, brand_id, price ASC, id DESC` | 기존 유지 |
| 전체 좋아요순 | `idx_products_deleted_like` | `deleted, like_count DESC, id DESC` | 기존 유지 |
| 브랜드별 좋아요순 | `idx_products_deleted_brand_like` | `deleted, brand_id, like_count DESC, id DESC` | 기존 유지 |

이 표는 엔티티 선언과 전용 DB의 실제 열·정렬 방향 대조 결과다. 근거는 [전후 계획 분석](baseline/index-review.md)에 보존한다.
API·Product 모델·QueryDSL 조회 조건·Projection·응답 필드·페이지 계약은 변경하지 않는다.
상품·브랜드 삭제 조건과 세 정렬의 동점 `id DESC`를 유지한다. COUNT 쿼리·캐시와 좋아요 집계의 운영 정책도 변경하지 않는다.

## 3. 측정 대상·역할

| 구분 | 고정 요청 | 목적 |
|---|---|---|
| A | `GET /api/v1/products?sort=latest&page=0&size=20` | 추가하는 전체 최신순 인덱스 비교 |
| B | `GET /api/v1/products?sort=price_asc&page=0&size=20` | 추가하는 전체 가격순 인덱스 비교 |
| C | `GET /api/v1/products?brandId={고정 브랜드 ID}&sort=price_asc&page=0&size=20` | 기존 브랜드별 가격순과 과제 필수 경로 비교 |

- 상품 **100만 개**와 현재 모델에 필요한 브랜드·사용자·실제 좋아요 관계를 재현 가능하게 준비한다.
- 각 요청을 **동시 1명·10명**으로 나눠 측정한다. 서로 다른 조회를 섞어 평균내지 않는다.
- Codex는 인덱스·재현 데이터·JMeter 파일·실행 및 비교 절차 준비와 실행 계획 분석을 담당한다.
- 초기에는 사용자 직접 실행으로 정했다. 변경 후에는 사용자가 Codex에 CLI 실행을 위임했고 동일 설정으로 수행했다.
- seed는 브랜드·사용자 각 1,000개, 브랜드별 상품 1,000개, 고정 브랜드 ID 7로 확정했다. 자세한 분포는 [SQL seed 실행 방식](trade_off/03-profile-sql-seed.md)을 따른다.
- 확정한 JMeter 설정은 워밍업 20초·본 측정 60초·2회 반복·ramp-up 5초다. 사용자 시간 단축 요청으로 3회에서 2회로 줄였다.

## 4. 전후 비교 규칙

- 전: 기존 상품 인덱스 4개. 후: 같은 데이터에 새 인덱스 2개만 추가한 6개 상태.
- 앱 코드의 조회 경로·입력·데이터·DB와 앱 버전·로그·연결 풀·측정 조건을 동일하게 유지한다.
- COUNT 로컬 캐시는 실험 실행에서만 `query.product-count-cache.ttl=0s`, 좋아요 집계는 `scheduler.like-count.enabled=false`로 고정한다. 운영 기본값을 바꾸지 않는다.
- SQL 캡처와 JMeter 부하 측정을 분리한다. 부하 측정 중 SQL 출력은 꺼서 전후 로그 비용을 맞춘다.
- 세 요청의 실제 목록 SQL과 COUNT SQL 모두 `EXPLAIN`·`EXPLAIN ANALYZE`를 확인한다. 전체 Projection·JOIN·삭제 조건·바인딩을 보존한다.
- key·접근 경로·예상/실제 rows·loops·정렬 단계·실행 시간·요청당 SQL 수와 JMeter 지연·처리량·오류율을 구분해 기록한다.
- 인덱스가 있다는 이유로 반드시 선택되거나 정확히 20행만 읽는다고 단정하지 않는다. JOIN과 통계의 영향을 확인한다.
- 정렬된 상품 ID·가격·좋아요 수·페이지 정보를 같은 입력으로 비교한다. 결과가 달라지면 성능 개선으로 인정하지 않는다.
- 특정 배수나 `Using index`·filesort 유무만을 성공 기준으로 고정하지 않는다. 줄인 비용·유지된 비용·새 쓰기/저장 비용을 설명한다.

## 5. 현재 환경에서 확인한 사실

2026-10-16 Docker와 MySQL을 읽기 전용으로 확인했다.

- 실행 중 MySQL 컨테이너는 `docker-mysql-1`, 이미지 `mysql:8.0`, 실제 버전은 `8.0.46`이다.
- `loopers`에 `points`, `product_like_counts` 등 이전 모델 테이블이 보인다. `information_schema.tables.TABLE_ROWS`는 0으로 표시됐으며 정확한 COUNT·현재 모델 검증은 수행하지 않았다.
- 이 스키마를 현재 애플리케이션의 완성된 실험 DB로 간주하지 않는다. 후속 실험은 별도 스키마에서 현재 전체 모델로 준비한다.
- local/test의 `ddl-auto=create`는 재시작 시 스키마를 재생성한다. 실험 데이터 준비 후 측정은 `ddl-auto=none`으로 고정해야 한다.
- `jmeter.bat`은 현재 PATH에서 확인되지 않았다. 설치 여부·실행 경로·버전은 후속 준비 단계에서 확인한다.
- 위 항목은 최초 준비 단계의 환경 조사 기록이다. 이후 전용 스키마·전체 엔티티 스키마·100만 seed를 준비하고
  INSERT 실행 전 EXPLAIN과 실제 적재·검증 결과를 보존했다. 이후 실제 HTTP SQL/응답과 JMeter 사전 확인 3건도 수집했다.
  첫 JMeter 본 측정은 최신순 1명 완료·10명 실패까지 수집했다. 이후 새 2개 적용·전후 쿼리 계획 수집은 완료했고 변경 후 부하는 미측정이다.

## 6. 후속 완료 조건

- [x] 기존 4개 정의를 유지하고 엔티티와 적용 SQL에 새 2개를 추가했다.
- [x] 격리 DB에 현재 모델의 상품 100만 개와 재현 seed를 준비했다. 인덱스 추가 후에도 이 데이터를 유지한다.
- [x] 3종 요청 × 동시 1명/10명의 JMeter 파일·초보자 실행 안내·결과 저장 규칙을 준비했다.
- [x] 실제 SQL·SQL 수·EXPLAIN·EXPLAIN ANALYZE를 전후 수집·분석했다. 요청당 SQL 수는 캡처한 동일 쿼리 경로를 유지한다.
- [x] 사용자 before 부분 기준선과 위임한 after 12개 결과의 표본·환경·한계, 별도 전체 응답 동일성을 기록했다.
- [x] 상품 목록 관련 회귀와 Checkstyle·ArchUnit·모듈 검사를 실행했다.
- [x] 실제 근거·결정과 차이·한계를 `result.md`에 작성하고 전체 요구사항의 상태를 갱신했다.

Redis 캐시·조회 Projection 축소·COUNT 정책 변경·페이지 API 변경·전체 6종 부하/깊은 페이지 실험은 이번 PR의 확정 범위에 포함하지 않는다.
