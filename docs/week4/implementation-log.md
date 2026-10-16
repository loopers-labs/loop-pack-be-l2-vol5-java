# 4주차 구현 기록

## 현재 상태

브랜드 7 가격순 인덱스·좋아요 카운터·브랜드 중복 조회 제거에 이어 기본 가격순 캐시를 구현했다. 가격순 구현·실행 검증은 21~27절의 상품 페이지 조회와 cache-aside + commit 후 DEL이다. 1~20절은 각 실험 시점의 기록이며 현재 API/캐시 수치와 합산하지 않는다. 정상 변경과 실패/늦은 저장의 정책을 구분한다.

측정 근거는 [보관 목록](measurements/README.md)의 JSON 9개를 커밋 대상으로 둔다. 현재 인기순은 [30절](#popular-cache-snapshot)의 60초 ID 순위다. [28절](#likes-cache-basic)은 좋아요마다 DEL하던 이전 비교와 326건 검사 기록이다. 문서에서 사용하는 인덱스·카운터 개별 원본 33개는 두 묶음의 `results["원래 파일명"]`에 값 그대로 보관했고 가격순 캐시 4개는 유지했다. 여기에 이전 좋아요순 비교·검사 2개와 현재 인기순 비교·검사를 함께 담은 1개를 추가했다. 원본 56개 파일의 바이트와 묶음의 각 JSON 값이 일치하는지 확인했다. 개별 중간 결과·실행 로그는 로컬에 남기고 Git에서 제외한다. 파일 정리는 새 성능 측정이나 숫자 변경이 아니다.

## Handler 결과를 먼저 보기

현재 SQL 01~14의 실제 Handler 차분과 원본 링크는 [Handler 검증 결과서](handler-results.md)에 모았다. 시간표와 분리해 **읽는 범위·정렬·카운터 변화**를 먼저 비교한다. 같은 상품 페이지 쿼리에서 관찰한 next 99 → 19가 읽기 감소의 핵심 근거다.

기존 원본 34개 경로의 차분을 대조했고, 빠져 있던 IN 강제 경로·JOIN 관련 5개 블록은 이번에 블록별 3회 실측해 보완했다. 없는 값을 실행 계획에서 추산해 채우지 않았다. 커버링·추가 정렬은 Handler에 직접 나타나지 않을 수 있으므로 실행 계획과 함께 해석한다. JOIN 운영 적용은 후속 검토로 남긴다.

## 준비와 DB 초기화

- 별도 측정용 MySQL: 3307/loopers_benchmark, MySQL 8.0.46·REPEATABLE READ. seed는 상품 10,000개·브랜드 100개·사용자 200명·좋아요 62,756건이다.
- 사용자 요청으로 이번에 추가한 idx_product_brand_price·idx_product_like_product만 삭제했다. 상품·좋아요 행 수와 기존 브랜드 인덱스·유니크 인덱스·기본키 유지 여부를 확인했다. [상태 변경 원본 · index-reset.json](measurements/index-results.json).
- 코드의 DB 정렬·페이지 구현과 인덱스 선언은 남아 있다. benchmark는 validate로 시작하며 실험 인덱스를 자동 재생성하지 않는다.
- 최초 API 기준선은 [baseline.json](measurements/index-results.json)에 보존한다. 현재 아래 SQL 측정과 서로 다른 범위의 기록이며 직접 시간 비교에 사용하지 않는다.

## 준비 측정: 전체 조회와 브랜드 7 조건 조회

[실행 SQL](../../scripts/sql/week4-01-brand-filter.sql), [측정 원본 · brand-filter-reset.json](measurements/index-results.json).

| 확인                       | 전체 미삭제 상품 조회 |     브랜드 7 미삭제 상품 조회 |
| -------------------------- | --------------------: | ----------------------------: |
| EXPLAIN 접근               |  ALL·인덱스 선택 없음 | ref·idx_product_brand_deleted |
| 상품 접근 노드 실제 rows   |                10,000 |                            99 |
| 반환 상품                  |                 9,699 |                            99 |
| Handler_read_key 차분      |                     1 |                             1 |
| Handler_read_next 차분     |                     0 |                            99 |
| Handler_read_rnd_next 차분 |                10,001 |                             0 |
| 서버 SQL 중앙값            |               4.550ms |                       0.356ms |

방식별 준비 10회·측정 30회로 먼저 실행하는 방식을 교대했다. 준비 포함 80회에서 상품 ID를 독립적인 seed 기대값과 비교했다. 전체 결과에서 브랜드 7을 고른 모든 상품 컬럼이 브랜드 조건 SQL의 결과와 같았다. 스키마·데이터는 변경하지 않았다.

반환 상품은 SQL이 애플리케이션에 내보낸 행 수다. 전체 스캔의 Handler_read_rnd_next 10,001은 끝 확인을 포함한 요청이며 상품 10,001개나 디스크 읽기 횟수를 뜻하지 않는다. 이 비교에는 가격 정렬·LIMIT·좋아요·HTTP가 없다.

## 1. ORDER BY만 추가

[실행 SQL](../../scripts/sql/week4-02-price-sort.sql), [측정 원본 · price-sort-reset.json](measurements/index-results.json).

| 확인                              | 브랜드 7 조회 | ORDER BY 추가 |
| --------------------------------- | ------------: | ------------: |
| 기존 브랜드 인덱스 접근 실제 rows |            99 |            99 |
| 반환 상품                         |            99 |            99 |
| Sort                              |          없음 |          있음 |
| Handler_read_key 차분             |             1 |             1 |
| Handler_read_next 차분            |            99 |            99 |
| 서버 SQL 중앙값                   |       0.345ms |       0.378ms |

방식별 준비 10회·측정 30회로 먼저 실행하는 방식을 교대했다. 준비 포함 80회 모두 상품 컬럼 집합이 같았다. 정렬 SQL의 ID 순서는 seed에서 독립적으로 계산한 가격 ASC·생성 시각 DESC·ID DESC 순서와 일치했다. LIMIT·좋아요·새 인덱스와 애플리케이션 코드는 변경하지 않았다.

EXPLAIN·EXPLAIN ANALYZE와 같은 연결의 Handler 전후 차이를 저장했다. 기존 인덱스로 99개를 읽는 작업은 같고 그 뒤 Sort가 추가됐다. Handler_read 값이 같아도 정렬 작업이 없는 것은 아니다. 이 카운터는 정렬 비교·CPU 작업을 직접 세지 않는다.

위 시간은 SHOW PROFILES의 서버 SELECT 시간이며 클라이언트·Java·HTTP 비용을 포함하지 않는다. 현재 통계와 로컬 소표본의 결과로, 작은 시간 차이를 API 개선율로 해석하지 않는다. 이번 문서 정리는 새로운 코드 변경이나 추가 테스트 실행이 아니다.

## 2. 고정 쿼리: 기존 브랜드 인덱스로 가격순 20개 조회

[실행 SQL](../../scripts/sql/week4-03-fixed-product-page.sql), [측정 원본 · fixed-page-brand-index.json](measurements/index-results.json), 실행기 `scripts/measure-week4-fixed-product-page.py`. 기존 브랜드 인덱스를 유지하고 힌트 없이 실행했다. 데이터·인덱스·통계는 변경하지 않았다.

| 확인                      | 결과                                            |
| ------------------------- | ----------------------------------------------- |
| EXPLAIN                   | ref·idx_product_brand_deleted·예상 rows 99      |
| Extra                     | Using index condition; Using filesort           |
| ANALYZE                   | Index lookup 99행 → Sort 상위 20행 → Limit 20행 |
| Handler_read_key 차분     | 1                                               |
| Handler_read_next 차분    | 99                                              |
| 그 밖의 Handler_read 차분 | 모두 0                                          |
| SQL 반환                  | 20개                                            |
| 서버 SQL 중앙값           | 0.170ms                                         |

준비 10회·측정 30회와 별도 Handler 측정 1회, 총 41회에서 상품 ID·브랜드 ID·이름·가격·생성 시각·순서를 독립적인 seed 기대값과 확인했다. 최초 실행은 날짜 표시의 소수점 형식 차이로 문자열 assertion에서 중단됐고 성공으로 기록하지 않았다. 날짜 값으로 정규화한 재실행 결과만 기록했다.

Handler는 일반 SELECT 하나의 같은 연결 직전·직후 차이이며 EXPLAIN ANALYZE는 따로 실행했다. Using filesort는 인덱스가 가격순 정렬을 해결하지 못해 별도 정렬했다는 뜻이지 디스크 정렬을 증명하지 않는다. Sort는 LIMIT을 고려해 상위 20개를 유지할 수 있으나 입력 후보 99개는 확인해야 했다. 서버 시간은 HTTP 시간과 구분한다.

기존 인덱스 순서를 이해하기 위해 브랜드 7·미삭제 구간을 ID 순으로 10개 조회했다. 가격은 26,000·96,000·66,000·36,000·6,000·76,000·46,000·16,000·86,000·56,000으로 가격순이 아니었다. 이 보조 쿼리에만 FORCE INDEX를 사용했다. 실제 페이지 덤프나 트리 높이 측정은 아니다. 읽는 과정의 설명과 실제 측정값을 design에 연결했다.

고정 쿼리 SQL 파일에 Handler 항목·같은 연결의 전후 차이 계산·측정 한계를 주석으로 추가했다. 직전 단계와 key 1·next 99가 같은 이유는 LIMIT이 반환 수만 줄이고 읽는 후보를 줄이지 않았기 때문이다. 사용자가 저장한 실행 계획 원문은 실행 시 SQL 오류가 나지 않도록 블록 주석으로 감싸 보존했다. 이번 변경은 설명 보강이며 새 측정은 하지 않았다.

## 3. 같은 쿼리 + 가격 정렬용 복합 인덱스

측정 DB에 scripts/sql/week4-product-price-index.sql의 CREATE INDEX·ANALYZE TABLE을 실행했다. 기존 브랜드 인덱스는 유지했다. [직접 확인 SQL](../../scripts/sql/week4-05-price-index.sql), [결과 원본 · fixed-page-price-index.json](measurements/index-results.json).

| 확인                   | 기존 브랜드 인덱스 | 가격 정렬용 복합 인덱스 |
| ---------------------- | -----------------: | ----------------------: |
| 상품 접근 실제 rows    |                 99 |                      20 |
| Sort                   |               있음 |                    없음 |
| 반환 상품              |                 20 |                      20 |
| Handler_read_key 증가  |                  1 |                       1 |
| Handler_read_next 증가 |                 99 |                      19 |
| 서버 SQL 중앙값        |            0.170ms |                 0.104ms |

측정 SQL은 이전과 정확히 같고 인덱스 힌트 없이 새 인덱스가 선택됐다. 일반 EXPLAIN 예상 rows는 여전히 99이지만 실제 접근은 20행이었다. LIMIT에 도달해 일찍 종료했기 때문이다. 가격 복합 인덱스는 Using filesort가 사라지고 Using index condition만 나타났으며 커버링 조회는 아니다.

준비 10회·측정 30회와 별도 Handler 1회, 총 41회에서 seed 기대 ID·브랜드·이름·가격·생성 시각·순서가 일치했다. 이전 기준선의 20행과 모든 컬럼·순서도 같았다. Handler는 같은 연결에서 일반 SELECT의 전후 차이를 구했고 ANALYZE는 별도 실행했다. key 1은 시작점을 찾는 요청, next 19는 다음 항목을 읽는 요청으로 총 20개를 반환하는 흐름과 맞는다. 기본키 재조회와 실제 페이지 방문 횟수를 이 값으로 세지 않는다.

키 순서 확인용 보조 조회에서는 같은 가격 6,000·같은 생성 시각 안에서 ID 7407 → 4407 → 1407, 그다음 더 이전 생성 시각의 9407 → 6407 → 3407이 나타났다. 이는 실제 행 값으로 정렬 키 순서를 확인한 예시이며 Leaf 물리 페이지를 덤프한 기록은 아니다.

기존 결과와 측정 시점이 다르고 추가 후 통계도 갱신했다. 서버 SQL 소표본의 시간 차이를 API 개선율로 해석하지 않는다. 이번에는 DDL·SQL 실험·문서만 변경했고 모델 선언은 이전에 이미 존재해 코드 수정·회귀 재실행은 없었다. 이름을 인덱스에서 얻는 커버링 비교와 상품 쓰기 비용은 미측정이다.

### 3.1 가격까지만 있는 인덱스와 동점 정렬까지 있는 인덱스

scripts/compare-week4-price-tie-index.py로 임시 `idx_product_brand_price_only(brand_id, deleted_at, price)`를 생성하고 ANALYZE TABLE 후 비교했다. SELECT 컬럼·WHERE·ORDER BY·LIMIT은 동일하고 비교에서만 FORCE INDEX로 후보를 분리했다. 힌트 없는 SQL이 두 후보 중 무엇을 선택하는지도 원본에 따로 기록했다. [확인 SQL](../../scripts/sql/week4-04-price-tie-index.sql), [결과 원본 · price-tie-index.json](measurements/index-results.json).

| 확인                     | 가격까지만 | 생성 시각 DESC·ID DESC 포함 |
| ------------------------ | ---------: | --------------------------: |
| 접근 실제 rows           |         99 |                          20 |
| Sort                     |       있음 |                        없음 |
| Handler key 증가         |          1 |                           1 |
| Handler next 증가        |         99 |                          19 |
| SQL 중앙값               |    0.163ms |                     0.102ms |
| 인덱스 할당 페이지       |         20 |                          25 |
| 16KiB 페이지 기준 할당량 |     320KiB |                      400KiB |

동일 세션에서 실행 순서를 교대하며 방식별 준비 10회·측정 30회, 총 80회에서 상품 ID·이름·가격·브랜드·생성 시각·순서가 seed 기대값과 같았다. 별도 Handler 측정의 두 후보 결과도 모든 필드·순서가 같았다. Handler는 일반 SELECT의 전후 차이이며 ANALYZE 실행을 포함하지 않았다.

크기는 mysql.innodb_index_stats의 size 페이지 수와 innodb_page_size로 확인했다. 행의 유효 바이트나 디스크 I/O 수가 아니다. 짧은 인덱스가 작지만 이번 쿼리의 추가 정렬을 제거하지 못했다. 인덱스 열 추가가 정확성에 필수인 것은 아니며 비용 선택의 문제다. 로컬 SQL 시간으로 API 개선율을 주장하지 않는다. 상품 쓰기 성능은 미측정이다.

finally에서 생성한 짧은 후보를 삭제했고 기존 인덱스 이름·컬럼·방향이 유지됐음을 확인했다. 전체 정렬 인덱스는 남아 있고 좋아요 새 인덱스는 여전히 없다. 모델·운영 코드 변경은 없었다.

### 3.2 사용자 변경 상태에서 자동 선택과 가격 후보 강제 사용

현재 상품 인덱스를 먼저 확인했다. 기존 idx_product_brand_deleted와 사용자 생성 idx_product_brand_price_only가 있고 전체 정렬 인덱스는 없었다. 스키마·통계·데이터를 변경하지 않고 자동 선택·가격 후보 강제·브랜드 후보 강제의 계획과 Handler·결과를 비교했다. [원본 · price-only-optimizer-choice.json](measurements/index-results.json).

| 확인                  |                 힌트 없음 |        가격 후보 FORCE INDEX |
| --------------------- | ------------------------: | ---------------------------: |
| 선택한 인덱스         | idx_product_brand_deleted | idx_product_brand_price_only |
| 실제 읽기             |                        99 |                           99 |
| Sort                  |                      있음 |                         있음 |
| Handler key·next 증가 |                      1·99 |                         1·99 |
| SQL 중앙값            |                   0.166ms |                      0.159ms |

자동·가격 강제 비교는 같은 프로파일 세션에서 실행 순서를 교대하고 방식별 준비 10회·측정 30회, 총 80회 모든 반환 컬럼·순서 일치를 확인했다. 별도 브랜드 강제 결과도 같았다. 작은 시간 차이와 표본 범위가 겹쳐 어느 쪽의 고정된 성능 우위로 해석하지 않는다.

실제 상품 SELECT의 optimizer trace를 수집했다. ref 후보 둘은 각각 rows=99·cost=34.65였고 기존 브랜드 인덱스가 chosen=true였다. range 후보 둘도 rows=99·cost=34.91로 표시됐으며 짧은 가격 후보는 chosen=false·cause=cost였다. 새 가격 후보가 예상 비용 우위를 제공하지 않는 점은 확인했지만 동률의 내부 선택 순서까지 확인하지 않았다. 최초 추적 수집은 표시용 SELECT가 상품 쿼리의 trace를 덮어써 유효하지 않았고, 표시 쿼리 없이 재수집한 실제 SELECT trace로 교체했다.

FORCE INDEX는 사용 가능 경로를 지정할 뿐 ORDER BY 전체 순서가 맞도록 인덱스를 바꾸지 않는다. 지금 고정 쿼리에서 짧은 가격 후보의 읽기·정렬 이득은 관찰하지 못했다. 가격 범위 등 다른 쿼리에서의 유용성은 별도 검토다. 사용자 직접 실험 중인 인덱스 구성은 그대로 유지했다.

사용자가 이해한 옵티마이저의 예상 비용 선택과 실제 실행 비교의 차이를 design과 04 SQL에 보강했다. 두 후보의 표시 비용이 같았다는 관찰과 동률 선택 규칙을 확인하지 않았다는 한계를 구분했다. 05 SQL에는 사용자 직접 실험 상태에서 짧은 후보 삭제·전체 정렬 인덱스 재생성 절차를 주석으로 명시했다. 이번 작업은 문서 보강이며 DB 변경·새 측정은 하지 않았다. 사용자의 다음 05 결과는 아직 확인하지 않았다.

### 3.3 MySQL 확장에서 Handler가 key 2·next 118 증가한 원인

사용자가 05 SQL의 일반 SELECT 전후에 관찰한 key 138 → 140·next 692 → 810을 조사했다. 파일의 측정 블록과 확장 실행 이력은 SHOW → SELECT → SHOW였다. 별도 MySQL 클라이언트에서는 SELECT만 실행하면 key 1·next 19 증가였고 SHOW만 반복해도 추가 증가가 없었다. 사용자의 실행 실수로 단정할 수 없었다.

benchmark DB의 general log를 잠깐 켜고 실제 편집기의 상품 조회를 실행했다. 같은 연결에서 원래 LIMIT 20 SELECT 다음에 확장이 `SELECT count(*) count0 FROM (원래 SELECT에서 LIMIT을 제거한 쿼리) temp`를 실행한 것을 확인했다. 화면에도 Total 99가 나타났다. 로그 설정은 원래 값으로 복원했다. [실제 SQL·재현 원본 · handler-extension-probe.json](measurements/index-results.json).

포착한 두 SQL을 한 연결에서 재현하니 key 증가 2·next 증가 118이 정확히 나왔다. 원래 SELECT의 1·19와 COUNT의 1·99가 합쳐진 결과다. 이 118을 원래 상품 페이지 SELECT 하나의 읽기량으로 해석하지 않는다. Handler는 의미가 없는 것이 아니라 측정 구간에 확장의 자동 COUNT가 포함됐던 것이다.

앞서 사용자에게 다른 쿼리를 직접 실행했을 가능성을 제시했지만 실제 원인은 확장의 자동 COUNT였다. 상품 SELECT 하나의 비교에는 확장의 COUNT가 없는 클라이언트/실행기를 쓰며 실제 API의 COUNT 비용은 별도로 포함해 평가한다. 현재 편집기에 저장 충돌 메시지가 있어 조사 중 05 SQL의 사용자 편집 내용은 변경하지 않았다.

05 SQL에 확장의 자동 COUNT와 key 2·next 118 해석, 자동 COUNT 없는 터미널 실행 명령을 추가했다. 별도 week4-05-handler-only.sql을 같은 MySQL 연결에서 실행하도록 분리했다. 해당 명령을 직접 실행해 BEFORE key 0·next 0, AFTER key 1·next 19와 상품 20개를 확인했다. 기존 사용자 실행 계획 원문을 블록 주석으로 보존했다. 인덱스·데이터 변경은 없다.

사용자 요청으로 SQL 번호를 04 가격까지만 있는 후보 → 05 생성 시각·ID 동점 정렬까지 포함하는 순서로 변경했다. 전체 정렬 인덱스의 Handler 전용 파일도 week4-05-handler-only.sql로 변경하고 문서 링크·실행 명령을 갱신했다. 기존 실행 계획·쿼리는 보존했다. 후보 99개를 끝까지 읽는 next 99에는 이후 98개와 끝 확인 요청이 포함됐고, LIMIT으로 멈추는 next 19와 구분한다. 인덱스·데이터·측정값은 변경하지 않았다.

## 4. id DESC만 제외한 후보 비교

`scripts/compare-week4-price-tie-index.py --without-id`로 idx_product_brand_price_time(brand_id, deleted_at, price, created_at DESC)을 임시 생성했다. 사용자 재적용한 05 전체 정렬 인덱스와 같은 쿼리·컬럼·WHERE·ORDER BY·LIMIT을 비교했다. 각 경로는 FORCE INDEX로 분리했고 두 후보가 있을 때 힌트 없는 선택은 05였다. [측정 원본 · price-without-id-index.json](measurements/index-results.json), [확인 SQL](../../scripts/sql/week4-06-implicit-id.sql).

| 확인                  |  id DESC 미명시 | 05의 id DESC 명시 |
| --------------------- | --------------: | ----------------: |
| 실제 접근 rows        |              99 |                20 |
| Sort                  |            있음 |              없음 |
| Handler key·next 증가 |            1·99 |              1·19 |
| 서버 SQL 중앙값       |         0.221ms |           0.137ms |
| 인덱스 할당 통계      | 25페이지·400KiB |   25페이지·400KiB |

방식별 준비 10회·측정 30회, 총 80회 실행 순서를 교대했다. 모든 반환 컬럼·순서가 seed 기대값과 같았다. Handler 측정도 별도 일반 SELECT의 같은 연결 전후 차이로 확인했다. SQL 시간은 로컬 서버 시간이고 HTTP·쓰기 비용을 포함하지 않는다.

id ASC로 바꾼 보조 조회는 same price/time의 1407 → 4407 → 7407을 반환했고 EXPLAIN에서 Using index·filesort 없음이었다. 이는 기본키 자동 포함으로 ID를 얻을 수 있음을 보여주지만 원래 쿼리의 id DESC 요구가 해결됐다는 뜻은 아니다. 보조 조회의 ORDER BY 변경은 원래 쿼리 성능 비교와 구분한다.

finally에서 이번 임시 후보만 삭제하고 기존 인덱스 이름·열·방향이 유지됐음을 확인했다. 모델·운영 코드·상품 데이터 변경은 없으며 ANALYZE TABLE로 통계는 갱신됐다. 05 전체 정렬 인덱스는 유지한다. SQL 파일은 임시 후보가 이미 삭제된 상태에서 재생성 절차와 관찰 결과를 설명한다.

## 5. 이름 포함·제외 커버링 비교

scripts/compare-week4-covering.py로 기존 05 인덱스에서 SELECT의 name만 포함·제외했다. WHERE·ORDER BY·LIMIT은 같고 힌트 없이 같은 인덱스를 선택했다. 준비 포함 총 82회에서 공통 컬럼·순서가 seed 기대값과 같았고 이름 포함 조회의 이름도 확인했다. [원본 · covering-name.json](measurements/index-results.json), [직접 확인 SQL](../../scripts/sql/week4-07-covering.sql).

| 확인                  | 이름 포함             | 이름 제외                |
| --------------------- | --------------------- | ------------------------ |
| EXPLAIN Extra         | Using index condition | Using where; Using index |
| ANALYZE 접근          | Index lookup          | Covering index lookup    |
| 실제 접근 rows        | 20                    | 20                       |
| Sort                  | 없음                  | 없음                     |
| Handler key·next 증가 | 1·19                  | 1·19                     |
| SQL 중앙값            | 0.136ms               | 0.116ms                  |

방식별 준비 10회·측정 30회로 먼저 실행하는 방식을 교대했다. Handler는 자동 COUNT 없는 클라이언트의 별도 SELECT 전후 측정이다. 인덱스·데이터·통계 변경은 없었다. 이름 제외는 반환 컬럼을 줄인 원리 실험이며 API 응답 개선안은 아니다.

### 5.1 이름을 인덱스에 포함하는 대안

사용자 제안으로 이름을 맨 뒤에 포함한 idx_product_brand_price_name을 임시 생성하고 ANALYZE TABLE 후 05와 비교했다. 이름을 포함한 같은 원래 쿼리에서만 비교하며 FORCE INDEX로 경로를 분리했다. 힌트 없는 선택도 원본에 저장했다. [원본 · covering-name-index.json](measurements/index-results.json).

| 확인                  | 05 인덱스    | 이름 포함 인덱스      |
| --------------------- | ------------ | --------------------- |
| ANALYZE 접근          | Index lookup | Covering index lookup |
| 실제 접근 rows·Sort   | 20·없음      | 20·없음               |
| Handler key·next 증가 | 1·19         | 1·19                  |
| SQL 중앙값            | 0.127ms      | 0.110ms               |
| 인덱스 할당량         | 400KiB       | 1,552KiB              |

방식별 준비 10회·측정 30회, 준비 포함 총 82회에서 반환된 전체 필드·순서가 앞선 seed 검증 결과와 같았다. 동일 세션에서 실행 순서를 교대했다. 임시 후보는 finally에서 삭제했고 기존 상품 인덱스가 남았음을 확인했다. 모델·운영 조회 코드는 변경하지 않았다.

시간은 서로 다른 두 실험을 합쳐 비교하지 않는다. 이름 인덱스 비교에는 05 최대 12.93ms·이름 후보 최대 9.83ms의 변동도 있어 작은 중앙값 차이를 안정적인 API 성능 이득으로 주장하지 않는다. 실제 API·상품 쓰기·MVCC 내부 기본키 접근은 측정하지 않았다. 페이지 할당량은 통계 기반이며 정확한 유효 바이트가 아니다. 이름 후보의 공간·관리 비용과 실제 이득을 추가 검토하기 전까지 05를 유지한다.

커버링은 인덱스 탐색을 없애는 것이 아니라 필요한 컬럼을 얻기 위한 실제 행 재조회를 줄인다는 이해를 design에 보강했다. 사용자 질문으로 기존 브랜드 인덱스와 가격 인덱스의 왼쪽 선두 컬럼 중복을 다음 검토 후보로 기록했다. 실제 코드의 브랜드 삭제 대상 ID 조회에는 ORDER BY id가 있어 긴 인덱스의 가격 순서와 다른 점을 확인했다. 이 문서 보강에서 DB 변경·대체 성능 측정은 하지 않았다.

## 6. 브랜드 인덱스와 가격 인덱스의 선두 컬럼 대체 비교

scripts/compare-week4-index-prefix.py로 브랜드 상품 조회·활성 상품 COUNT·브랜드 삭제 대상 ID 조회를 비교했다. 기존·긴 인덱스를 각각 FORCE INDEX로 분리하고 자동 선택도 기록했다. DDL·데이터·통계를 변경하지 않았다. [확인 SQL](../../scripts/sql/week4-08-index-prefix.sql), [원본 · index-prefix.json](measurements/index-results.json).

| SQL 형태              | 기존 인덱스 중앙값 | 긴 인덱스 중앙값 | 확인                             |
| --------------------- | -----------------: | ---------------: | -------------------------------- |
| 브랜드 상품 99개      |            0.299ms |          0.310ms | 같은 컬럼 집합, 둘 다 99개 접근  |
| 활성 상품 COUNT       |            0.120ms |          0.122ms | 둘 다 99개 집계, 결과 99         |
| 삭제 대상 ID 오름차순 |            0.116ms |          0.140ms | 같은 ID·순서, 긴 인덱스에만 Sort |

각 형태에서 방식별 준비 10회·측정 30회, 전체 240회에서 seed ID·상품 컬럼 집합·COUNT·ID 정렬을 검증했다. 일반 상품 조회는 ORDER BY가 없으므로 반환 순서를 동일성 조건으로 삼지 않았다. ID 조회는 순서까지 비교했다. 실행 순서는 교대했으며 시간은 서버 SQL 기준이다.

세 형태 모두 Handler next 증가 99였다. 브랜드 상품·ID 조회는 key 증가 1이고 COUNT는 활성 브랜드 기본키 조회가 포함돼 key 증가 2였다. 같은 연결의 일반 SELECT 전후 차이이며 자동 COUNT를 보내는 확장은 사용하지 않았다. COUNT·ID 조회는 두 경로 모두 커버링이었지만 ID 조회의 긴 경로에 Using filesort가 추가됐다. 힌트 없는 세 SQL은 기존 짧은 인덱스를 선택했다.

인덱스 할당 통계는 짧은 인덱스 18페이지·288KiB, 긴 인덱스 25페이지·400KiB였다. 현재 통계값이며 앞선 다른 측정과 정확히 고정된 크기로 해석하지 않는다. 삭제 작업 전체·큰 브랜드·동시 요청·쓰기는 미측정이다. 결과는 선두 컬럼으로 탐색 대체가 가능하되 ID 정렬 비용이 다르다는 것까지이며, 기존 인덱스는 삭제하지 않았다.

## 7. ① 가격 우선 인덱스 비교

scripts/compare-week4-price-tie-index.py --price-first로 임시 가격 우선 인덱스를 생성·ANALYZE 후 05와 비교했다. SELECT·WHERE·ORDER BY·LIMIT은 그대로이며 인덱스의 가격 위치만 바꿨다. [직접 SQL](../../scripts/sql/week4-09-price-first.sql), [원본 · price-first-index.json](measurements/index-results.json).

| 확인               |          가격 우선 |    브랜드 우선 05 |
| ------------------ | -----------------: | ----------------: |
| 실제 접근          | Index scan 1,600행 | Index lookup 20행 |
| Filter 후 반환     |                 20 |                20 |
| Sort               |               없음 |              없음 |
| Handler first 증가 |                  1 |                 0 |
| Handler key 증가   |                  1 |                 1 |
| Handler next 증가  |              1,599 |                19 |
| SQL 중앙값         |            0.992ms |           0.112ms |

읽는 순서로 요약하면 가격 우선은 **1,600행 읽음 → 추가 정렬 없음 → 20행 반환**, 브랜드 우선은 **20행 읽음 → 추가 정렬 없음 → 20행 반환**이었다. 같은 20개 결과를 얻었지만 읽기량은 달랐다. 1,600은 이번 쿼리의 실제 읽기량이며 전체 테이블을 끝까지 읽었다는 뜻이 아니다.

방식별 준비 10회·측정 30회, 전체 80회에서 실행 순서를 교대하고 모든 상품 필드·순서가 seed 기대값과 같았다. 별도 Handler 측정 결과도 일치했다. 서버 SQL 시간이며 API·쓰기·동시 부하는 포함하지 않는다.

가격 우선 EXPLAIN은 type=index·예상 rows=20·Using where였지만 실제 접근은 1,600행이었다. 예상 20을 실제 읽기량으로 해석하지 않는다. 같은 ORDER BY의 Sort가 없어도 필터 때문에 넓게 읽을 수 있다는 사례다. 자동 선택은 05 브랜드 우선 인덱스였으므로 이번 데이터에서 옵티마이저가 가격 우선 후보를 잘못 선택했다고 기록하지 않는다.

현재 고정 seed로 관찰한 값이다. 가격 분포가 바뀌면 가격 우선 읽기량도 바뀔 수 있다. 후보는 finally에서 삭제하고 기존 인덱스 이름·컬럼·방향 유지 여부를 확인했다. 상품 데이터·모델·운영 코드 변경은 없고 통계만 갱신했다.

## 8. ②~④ 연속 검증

실행기 scripts/compare-week4-index-conditions.py, [원본 · index-conditions.json](measurements/index-results.json). 각 비교 쌍에서 방식별 준비 10회·측정 30회, 전체 320회 실행 순서를 교대했다. 결과의 ID·브랜드·이름·가격·생성 시각·순서를 독립적인 seed 기대값과 확인했다. 별도 Handler 측정은 같은 연결의 일반 SELECT 전후 차이로 수행했고 자동 COUNT는 없다. 시간은 서버 SQL 기준이다.

### 8.1 IN (7, 8)

| 확인                  | brand_id=7 | brand_id IN (7,8) |
| --------------------- | ---------: | ----------------: |
| 실제 상품 접근        |         20 |               198 |
| Sort                  |       없음 |              있음 |
| Handler key·next 증가 |       1·19 |             2·198 |
| SQL 중앙값            |    0.135ms |           0.317ms |

단일 브랜드는 **20행 읽음 → 추가 정렬 없음 → 20행 반환**이었다. 두 브랜드는 **198행 읽음 → 추가 정렬 있음 → 20행 반환**이었다. 두 브랜드 쿼리에 05를 강제한 계획도 같은 198행 읽기·Sort·20행 반환을 보였다. “브랜드별로 가격순”인 인덱스가 “두 브랜드를 합친 전체 가격순”까지 그대로 만드는 것은 아니라는 점을 이번 두 경로에서 확인했다. 모든 IN 쿼리·모든 인덱스의 일반 규칙으로 확대하지 않는다.

단일 브랜드는 05를 선택했고 IN은 기존 짧은 브랜드 인덱스를 선택했다. 보조 확인으로 05를 강제해도 198개 접근·Sort였고 IN 기대 결과와 모든 값이 같았다. 두 조건의 결과를 서로 같아야 한다고 검증하지 않고 각각의 대상 브랜드로 기대값을 계산했다. [SQL](../../scripts/sql/week4-10-brand-in.sql).

### 8.2 인덱스의 deleted_at 포함 여부

CREATE TABLE LIKE + INSERT SELECT로 상품 테이블을 복제했다. 복제에만 삭제 조건 미포함 인덱스를 추가해 05와 강제로 분리 비교했다. 원래 분포에서는 둘 다 실제 20행·next 19였다. 복제의 가격순 활성 상품 첫 70개만 추가 삭제해 남은 활성 상품을 29개로 만든 뒤 결과를 재검증했다.

| 저가 삭제 후          | deleted_at 미포함 | 05에 포함 |
| --------------------- | ----------------: | --------: |
| 실제 접근             |                91 |        20 |
| Sort                  |              없음 |      없음 |
| Handler key·next 증가 |              1·90 |      1·19 |
| SQL 중앙값            |           0.185ms |   0.132ms |

91개에는 추가 삭제 70개·원래 삭제 상품 하나·반환한 활성 20개가 포함됐다. 같은 데이터 상태의 두 쿼리는 모든 값·순서가 같았다. 복제 테이블은 finally에서 삭제했다. 실제 product의 행 수·ID 합·가격 합·활성 수와 실제 인덱스 목록은 전후 일치했다. [SQL](../../scripts/sql/week4-11-deleted-condition.sql).

### 8.3 OFFSET 60과 커서

| 확인                  |  OFFSET |    커서 |
| --------------------- | ------: | ------: |
| 실제 상품 접근        |      80 |      20 |
| Sort                  |    없음 |    없음 |
| Handler key·next 증가 |    1·79 |    3·19 |
| SQL 중앙값            | 0.177ms | 0.191ms |

브랜드 7의 61번째부터 20개를 같은 05 인덱스에서 비교했다. 커서 앵커는 60번째 상품의 가격 66,000·시각 2026-01-28 00:00:00·ID 9207이며 측정 밖에서 준비했다. 커서의 세 OR 조건에 따른 range 접근에서 key 증가 3이 관찰됐다. 모든 반환 컬럼·순서는 OFFSET과 같았다. 읽기 감소에도 이번 99개 후보·얕은 페이지에서는 시간 우위를 보이지 않았다. [SQL](../../scripts/sql/week4-12-offset-cursor.sql).

### 실험 범위와 유지 상태

실제 API·COUNT·좋아요·네트워크·쓰기·동시 변경은 이 비교에 포함하지 않았다. 날짜 문자열 형식은 datetime 값으로 정규화했다. 브랜드 100개의 프로젝트 seed와 활성 상품 99개인 브랜드 7을 기준으로 OFFSET 60을 비교했다. 후보·복제 생성과 복제 변경을 실제 상품 데이터 변경으로 기록하지 않는다. 실험 후 복제 제거·실제 데이터와 인덱스 보존을 확인했다. DDL과 조회 후보는 SQL에 명시하고 원본 결과를 보존했다. 모델·운영 코드 변경이 없어 이번에는 기능 회귀를 재실행하지 않았다.

## 9. 좋아요 인덱스 재적용과 실제 API 전후 확인

현재 상품 DB의 기존 브랜드·05 가격 인덱스를 확인한 뒤 같은 benchmark 앱 프로세스에서 좋아요 인덱스 없는 API를 먼저 측정했다. [적용 전 API · final-before-like-index-api.json](measurements/index-results.json). 이후 실제 product_like에 product_id 인덱스만 생성·ANALYZE하고 같은 앱에서 재측정했다. [적용 후 API · final-after-like-index-api.json](measurements/index-results.json). 매 단계 준비 10회·측정 30회, 동시 요청 1개로 모든 응답 필드를 독립 seed 기대값과 검증했다. 두 단계의 응답 체크섬·전체 응답도 같았다.

| HTTP·실제 SQL        | 좋아요 인덱스 없음 | 적용 후 |
| -------------------- | -----------------: | ------: |
| HTTP 중앙값          |            25.32ms | 13.12ms |
| 평균                 |            27.18ms | 16.94ms |
| p95                  |            38.95ms | 40.99ms |
| 업무 SELECT 수       |                  5 |       5 |
| 상품 페이지 접근     |     20행·Sort 없음 |    동일 |
| 좋아요 접근          |             62,756 |     248 |
| 상품 응답·totalCount |            20개·99 |    동일 |

고정 상품 20개의 좋아요 SQL 자체도 실제 테이블에서 추가 전후 준비 10회·측정 30회 및 별도 Handler 실행을 확인했다. 전체 82회 집계가 seed 기대값과 같았고 18그룹·총 248건이었다. [집계 원본 · final-like-index.json](measurements/index-results.json), [13 SQL](../../scripts/sql/week4-13-like-product-index.sql).

집계 SQL 중앙값은 7.608ms → 0.259ms, Handler next 증가 62,756 → 248이었다. 기존 경로의 key 249·rnd_next 19에는 임시 테이블 집계·읽기 요청이 포함돼 키 조회 횟수를 상품 수로 해석하지 않는다. 새 경로는 key 증가 20과 range scan 248행·Group aggregate였다. 행 수·ID·사용자·상품 ID 합계로 실제 좋아요 데이터가 유지됐음을 확인했다.

실제 API의 5개 SELECT를 general log에서 같은 앱 연결로 수집했다. 브랜드 단건 확인 1, 활성 상품 COUNT 1, 상품 페이지 1, 브랜드 묶음 조회 1, 좋아요 집계 1이었다. COUNT는 기존 브랜드 인덱스로 99개, 상품 페이지는 05로 20개·Sort 없음, 좋아요는 새 인덱스로 248개를 읽었다. 일반 로그 설정은 원래 값으로 복원했다. 측정 후 시작한 benchmark 앱은 종료했다.

좋아요 인덱스의 등록·취소 비용은 이전 복제 실험에서 함께 측정했으며 읽기 이득만으로 비용이 없다고 판단하지 않는다. 원본 [like-index-read-write.json](measurements/index-results.json): 등록 중앙값 1.249 → 1.277ms·취소 1.241 → 1.345ms, 보조 인덱스 할당량 약 1.5MiB 증가. 반복값이 겹치는 로컬 단일 관계 실험이므로 고정된 쓰기 증가율로 해석하지 않는다.

이번 최종 선택은 DB 가격순 offset 페이지·별도 묶음 좋아요 집계·05 상품 가격 인덱스·13 좋아요 인덱스다. 두 모델 선언과 실제 DB를 대조했다. 페이지 선행 JOIN은 SQL 실험 결과로 별도 집계를 유지했고 JOIN API 비교는 미실시다. HTTP 중앙값 개선을 관찰했지만 p95 개선을 확인하지 못했으며 실제 부하·동시 요청·다른 브랜드 분포는 검증 범위 밖이다. 회귀 검사는 다음 결과에 기록한다.

### 최종 회귀·문서 대조

Java 21·Docker Testcontainers 연결에서 `:apps:commerce-api:check -Pversion=week4-final-read`를 실행해 BUILD SUCCESSFUL로 종료했다. XML 결과 49개 suite·260개 테스트, 실패·오류·skip 0을 확인했다. 상품·브랜드·좋아요·주문·포인트 및 ArchUnit을 포함한다. Checkstyle main/test는 현재 입력 기준 UP-TO-DATE로 성공했다. 테스트 기대값·lint·아키텍처 규칙은 변경하지 않았다.

코드의 가격순 조회·COUNT·묶음 집계와 두 모델의 인덱스 선언을 실제 API SQL·benchmark 인덱스와 대조했다. 기존 API 계약과 주문·재고 판단을 유지하며 캐시를 추가하지 않았다. 최종 선택·측정 조건·한계·후속 검토를 design·plan·setup에 갱신했다. 커밋·push는 하지 않았다. 사용자는 11·12와 새 13 SQL을 직접 확인한 뒤 추가 질문을 이어갈 수 있다.

## 10. 실제 API의 별도 집계와 페이지 선행 JOIN 후보

`scripts/experiments/join-api.init.gradle`로 실험 Java 소스만 opt-in하여 같은 상품 Controller가 실험 전용 ProductQueryService를 받도록 했다. AtomicBoolean으로 HTTP 요청 전에 경로만 전환한다. 별도 집계는 실제 기존 서비스로 위임하고 JOIN은 읽기 전용 같은 JPA 트랜잭션 안에서 JdbcTemplate으로 실행한다. 일반 소스셋·운영 코드에는 후보를 넣지 않았다. [실험 테스트](../../scripts/experiments/java/com/loopers/experiment/ProductJoinApiExperimentTest.java), [SQL](../../scripts/sql/week4-14-page-first-join.sql), [측정 원본 · join-api-final.json](measurements/index-results.json).

| 실제 HTTP 비교   |     기존 별도 집계 | JOIN 후보 |
| ---------------- | -----------------: | --------: |
| 중앙값           |            16.35ms |   10.60ms |
| 평균             |            18.36ms |   10.76ms |
| p95              |            25.17ms |   12.06ms |
| 실제 업무 SELECT |                  5 |         3 |
| 고정 응답        | seed 기대값과 일치 |      동일 |

동일 서버·같은 persistent Java HttpClient에서 실행 순서를 교대했다. 방식별 준비 10회·측정 30회, 80개 고정 요청의 모든 응답 필드를 독립 seed 기대값과 확인했다. 추가 10개 입력×두 경로에서 다음 페이지·빈 페이지·브랜드 없는 조회·삭제/없는 브랜드·잘못된 page/size·큰 offset·latest·likes_desc의 상태 코드와 전체 JSON이 같았다. 오류 계약을 별도 새 구현으로 복제하지 않고 기존 경로에 위임했다.

시간 측정 뒤 general log에서 경로별 실제 SQL을 별도로 수집하고 EXPLAIN ANALYZE했다. 같은 앱 연결의 split 5개·join 3개 SELECT를 확인했다. 로그 설정은 원래 상태로 복원했다. JOIN은 브랜드 확인과 COUNT를 별도로 유지하고 상품·브랜드·좋아요를 하나의 JDBC 쿼리로 받았다. 고정 페이지는 실제 상품 20행·좋아요 lookup 평균 12.4×20회, LEFT JOIN 250행→임시 집계 20행→Sort 20행이었다. 상품 후보 Sort는 없었고 JOIN 후 Sort는 남았다.

첫 실행은 테스트 픽스처가 시스템 속성으로 DB 주소를 Testcontainer의 빈 DB로 바꿔 validate에서 brand 테이블 누락으로 실패했다. 측정용 주소를 실험 @SpringBootTest 속성에 명시한 재실행만 성공 기록에 포함했다. 재실행에서 HTTP 실험 1건과 자동 포함 ArchUnit 1건, 총 2건 통과(실패·오류·skip 0), 실험 소스 포함 Checkstyle test 통과. 실험 서버는 테스트 종료 시 정리됐다.

현재 실험에서는 JDBC 투영으로 상품의 재고·updated_at 등 응답에 쓰지 않는 엔티티 컬럼도 덜 가져오고 브랜드 조회도 통합했다. 따라서 순수 JOIN 연산 하나의 효과로 시간 차이 전부를 해석하지 않는다. 기존 urllib 측정과 연결·클라이언트 방식이 달라 이번 두 경로 사이에서만 비교한다. 로컬 30표본·동시 요청 1개이며 실제 운영 지연·다른 분포·고부하·쓰기 경쟁은 미검증이다.

현재 조건에서 JOIN은 실제 API 비용을 줄인 유력 후보다. 하지만 실험용 서비스는 연구용 대체 구현이므로 그대로 운영에 채택하지 않는다. 사용자와 이 결과를 검토한 뒤 조회 포트·투영과 회귀를 갖춘 실제 적용을 별도 진행한다. 운영 코드는 별도 집계를 유지하고 캐시는 보류한다.

실험 init script를 제외한 일반 빌드에서 상품·좋아요·LayeredArchitectureTest 50건 통과(실패·오류·skip 0), Checkstyle main/test 통과를 확인했다. 일반 실행 결과에는 실험 테스트가 포함되지 않았음을 XML 목록으로 확인했다. 관련 검사 후 추가 코드 변경은 없다.

## 11. Handler 증거 전체 정리와 누락 측정 보완

기존 JSON의 handler_delta를 직접 읽어 현재 SQL 흐름 01~13의 34개 경로를 결과서에 정리했다. 각 행은 해당 원본 링크를 포함한다. first·key·next·rnd_next를 함께 보이고 last·prev·rnd 차분이 모든 경로에서 0임도 확인했다. 시간 차이가 작을 때 주요 근거는 같은 쿼리의 읽기 감소이며, 다른 구조 쿼리의 카운터를 하나의 상품 수나 절대 성능 순위로 해석하지 않는다.

기존 실제 API JOIN 원본에는 SQL·계획·시간은 있었지만 Handler가 없었다. scripts/measure-week4-handler-supplement.py로 IN 05 강제·상품 페이지+좋아요 별도 집계·상품/브랜드/좋아요 JOIN·split 업무 SELECT 전체·join 업무 SELECT 전체를 보완 측정했다. [원본 · handler-supplement.json](measurements/index-results.json).

| 추가 측정 블록                         | key 증가 | next 증가 | rnd_next 증가 |
| -------------------------------------- | -------: | --------: | ------------: |
| IN·05 강제                             |        2 |       198 |             0 |
| 실제 SQL의 상품 페이지+좋아요 2 SELECT |       22 |       267 |             0 |
| 상품·브랜드·좋아요 JOIN 1 SELECT       |      272 |       267 |            42 |
| split 업무 SELECT 5개 재실행           |       26 |       366 |             0 |
| join 업무 SELECT 3개 재실행            |      275 |       366 |            42 |

각 블록은 같은 읽기 전용 트랜잭션 안에서 일반 SELECT 전후 SHOW SESSION STATUS 차이를 구했다. 독립 연결에서 3회씩 실행해 총 15개 블록의 차분·반환 행 일치를 확인했다. IN은 기존 독립 기대값과, JOIN의 모든 상품·브랜드·좋아요 값은 HTTP 실험의 독립 seed 기대값과 대조했다. 실제 데이터·인덱스 전후 유지도 확인했다. EXPLAIN ANALYZE는 Handler 구간 밖에서 실행했다.

API 전체 값은 실제 API에서 수집한 업무 SQL을 클라이언트로 재실행한 범위다. HTTP 연결의 모든 세션·드라이버 SQL을 포착한 값이라고 기록하지 않는다. JOIN은 next가 같고 임시 집계·테이블 읽기 때문에 key·rnd_next가 더 많았다. 이는 SQL 내부 작업량과 API 왕복 감소가 별개임을 보여준다. 앞선 HTTP 결과만으로 Handler가 줄었다고 추정하지 않았다.

프로젝트 코드·인덱스·실제 데이터를 변경하지 않은 읽기 검증과 문서 작업이다. 추가 기능 회귀는 필요 없어 재실행하지 않았고 Python 검증 assertion과 3회 실측으로 결과를 확인했다. JOIN 채택은 이번 작업에서 확정하지 않았다.

## 12. 10만 건 스케일 재현과 데이터 쏠림 측정 기록

`loopers_benchmark_100k`를 `mysql-benchmark` 컨테이너(포트 3307)에 새 DB로 만들고, `loopers_benchmark`의 `brand`·`product`·`product_like`·`users` 스키마를 복제했다. [seed-week4-100k.py](../../scripts/seed-week4-100k.py) 실행 결과: 상품 100,000(비삭제 96,990)·브랜드 100·유저 200·좋아요 628,186. `--verify-only`로 재확인 가능.

복제 과정에서 `idx_product_price_first`가 원본 `loopers_benchmark`에 여전히 남아 있는 걸 발견했다(9절의 05 채택 당시 "비교 후 삭제했다"던 후보). design.md·7절 기록과 실제 DB가 어긋나 있었다 — Java 엔티티에 이 인덱스 선언이 없어 JPA가 재생성한 것은 아니었고, 삭제 SQL이 실제로 실행되지 않은 상태로 남아 있었던 것으로 보인다. `loopers_benchmark`·`loopers_benchmark_100k` 양쪽에서 `DROP INDEX idx_product_price_first ON product;`로 제거하고 `docs/week4/setup.md`의 어긋난 서술도 정정했다.

[week4-15-100k-scale-check.sql] 결과: `EXPLAIN`(통계 갱신 전/후 모두) `key=idx_product_brand_price`, `rows=990`, `Extra=Using index condition`. Handler `read_key` +1, `read_next` +19 — 1만 건 기록(`rows=99`, `+1`/`+19`)과 `next` 증가분이 같다.

[week4-16-100k-like-join-check.sql] 결과: `EXPLAIN`은 `Covering index range scan on product_like using idx_product_like_product` 유지. Handler 측정 시 앞선 `COUNT(*)` 등 쿼리가 세션 카운터를 이미 올려둬 절대값이 커 보이므로 전/후 차분만 사용 — `read_key` +20, `read_next` +93으로 IN 목록 20개 분량만 읽었다(전체 628,186개 스캔이 아님).

[week4-17-100k-hotspot.sql] 결과: `loopers_benchmark_100k`를 복제한 `loopers_benchmark_100k_hotspot`에 [seed-week4-100k-hotspot.py](../../scripts/seed-week4-100k-hotspot.py)로 신규 유저 50,000명(id 201~50200)을 추가하고 상품 97407(브랜드 7·가격 6000·1페이지 1번째)에만 전원 좋아요를 달았다. 검증: 유저 50,200명·상품 97407 좋아요 50,002개·`product_like` 총 678,186행.

| 비교                                                   | 핫스팟 페이지(97407 포함)                   | 일반 페이지(97407 미포함) |
| ------------------------------------------------------ | ------------------------------------------- | ------------------------- |
| `EXPLAIN` 계획                                         | `idx_product_like_product` 커버링 범위 스캔 | 동일                      |
| 실제 rows(Filter·스캔 단계)                            | 50,093                                      | 255                       |
| `EXPLAIN ANALYZE` 전체 시간(최상위 `actual time` 끝값) | 15.4ms                                      | 0.243ms                   |
| `SHOW PROFILES` Duration(연속 2회)                     | 8.58ms·8.65ms                               | 0.29ms·0.40ms             |
| Handler `read_next` 증가분                             | +50,093                                     | +255                      |

처음에는 `docker compose exec`를 매번 새로 띄워 쿼리 시간을 재다가, 접속 오버헤드(100ms대)가 쿼리 자체보다 커서 폐기했다. 하나의 연결 안에서 `SET profiling = 1;` 후 반복 실행하고 `SHOW PROFILES;`로 재는 방식으로 바꿨다. 각 조건 2~3회의 작은 로컬 표본이며, 운영 동시 부하·네트워크 왕복은 포함하지 않는다.

이 작업은 benchmark 전용 DB(`loopers_benchmark_100k`, `loopers_benchmark_100k_hotspot`)에 대한 조회 검증이다. commerce-api 운영 코드·DB 설정과 실제 1만 건 benchmark 데이터는 변경하지 않았다.

## 13. 10만 건 추가 전후·동일 페이지 쏠림·쓰기·실제 API 검증

[실행기](../../scripts/compare-week4-100k.py), [SQL·시간·Handler·계획·쓰기 표본 원본 · 100k-read-write-skew.json](measurements/index-results.json), [API 실행기](../../scripts/measure-week4-100k-api.py), [API 원본 · 100k-final-api.json](measurements/index-results.json).

### 비교 조건과 정확성

1만 건에서 비교한 모든 후보를 반복하지 않고 최종 상품 가격 인덱스·좋아요 product_id 인덱스의 추가 전후만 비교했다. 10만 건 DB에서 CREATE TABLE LIKE + INSERT SELECT로 네 테이블을 복제하고 control에서 해당 추가 인덱스만 제거했다. 기존 브랜드·유니크·PK는 유지했다. 같은 통계 갱신 후 힌트 없는 SQL로 실행 순서를 교대했다. 읽기는 방식별 준비 10회·측정 30회, 단일 클라이언트다. Docker 실행·접속 시간은 제외하고 SHOW PROFILES 서버 시간을 사용했다. EXPLAIN ANALYZE와 Handler는 시간 측정 밖에서 수집했다.

상품 반환 필드·순서를 독립 seed 공식으로 검증했다. 날짜 문자열은 DATE_FORMAT으로 초 단위로 정규화했다. 좋아요 비교 SQL은 결과 순서를 고정하려고 ORDER BY product_id를 명시했다. 실제 API의 원래 SQL은 아래 별도 캡처로 검증했다. 원본과 복제의 행 수뿐 아니라 쓰기 복구 후 모든 컬럼을 NULL-safe 비교했다. 원본 네 테이블의 행 수·ID 합·전체 컬럼 CRC32 XOR과 인덱스 정의도 전후 일치했다. CRC는 보조 변경 탐지이지 충돌 없는 증명은 아니다.

| SQL 비교           | 추가 전 중앙값 | 추가 후 중앙값 | 실제 읽기·정렬                                      |
| ------------------ | -------------: | -------------: | --------------------------------------------------- |
| 상품 가격순 20개   |        1.323ms |        0.327ms | 990행·Sort → 20행·Sort 없음                         |
| 페이지 좋아요 집계 |       60.407ms |        0.379ms | 유니크 인덱스 전체 628,186행 → product_id 범위 93행 |

상품 Handler key/next는 1/990 → 1/19, 좋아요 next는 628,186 → 93이었다. 단순 시간 배율보다 읽기·정렬 변화가 핵심 근거다.

### 같은 페이지의 쏠림 전후

두 DB에서 같은 20개 상품 ID로 비교했다. 기대 결과 차이는 상품 97407의 좋아요 2 → 50,002뿐이다. 일반 페이지와 핫스팟 페이지가 서로 다른 기존 12절 비교를 보완한다.

| 조건                      | 읽은 좋아요 행 | SQL 중앙값 | Handler key/next |
| ------------------------- | -------------: | ---------: | ---------------- |
| 일반 10만 건 DB의 1페이지 |             93 |    0.320ms | 20/93            |
| 핫스팟 DB의 같은 1페이지  |         50,093 |    6.850ms | 20/50,093        |

두 계획 모두 커버링 범위 스캔이다. 범위 탐색은 유지됐지만 집계량이 늘었다. 읽기 쏠림 실험이며 좋아요 쓰기 요청 빈도·동시 경합은 측정하지 않았다.

### 쓰기 비용과 저장 공간

START TRANSACTION + DML + COMMIT 서버 시간 합계다. 가격 수정과 논리 삭제는 여러 상품 ID를 사용하고 각 작업을 별도 commit한 뒤 복구도 별도 commit했다. 등록은 같은 실험용 상품 ID를 재사용한다. 준비 10회·측정 최대 50회 중 원래 삭제된 상품 한 개는 양쪽에서 제외해 상품 작업별 49표본, 좋아요는 50표본이다. 복구·정리 작업 시간도 원본에 따로 보존했다.

| 작업           | 추가 전 중앙값 | 추가 후 중앙값 |
| -------------- | -------------: | -------------: |
| 상품 등록      |        1.834ms |        1.874ms |
| 상품 가격 변경 |        1.228ms |        1.322ms |
| 상품 논리 삭제 |        1.211ms |        1.170ms |
| 좋아요 등록    |        1.801ms |        2.066ms |
| 좋아요 취소    |        1.541ms |        1.432ms |

좋아요 쓰기는 FK 없는 복제 테이블에 seed에 없는 user_id=201과 회차별 상품을 사용한다. 애플리케이션 사용자 검증을 포함한 API 쓰기 실험이 아니다. 같은 관계 반복에 한정됐던 1만 건 실험을 보완하지만 지속 부하·랜덤 쓰기·동시 등록/취소 실험은 아니다. 표본 변동으로 추가 후 오히려 짧은 중앙값도 있어 고정된 악화율이나 쓰기 비용이 없다는 결론을 내리지 않는다.

동일 ANALYZE 후 보조 인덱스 할당량은 상품 5,783,552 → 11,567,104 bytes(차이 5.52MiB), 좋아요 26,820,608 → 42,876,928 bytes(차이 15.31MiB)였다. 유효 데이터 바이트나 순수 인덱스 키 크기가 아닌 테이블의 보조 인덱스 페이지 할당 통계다.

### 최종 인덱스의 실제 API

현재 운영 소스를 bootJar로 빌드하고 일반·핫스팟 DB별 독립 JVM으로 실행했다. 기존 설정 파일은 수정하지 않고 명령행으로 DB 주소와 포트 18082/18083만 지정했으며 ddl-auto=validate를 유지했다. 각 DB에서 누락된 example·orders·order_item·point_balance·point_grant·point_usage의 빈 스키마를 잠시 복제하고 종료 후 빈 상태를 확인해 제거했다. 상품·좋아요 데이터와 인덱스는 변경하지 않았다.

브랜드 7·price_asc·page=0·size=20에서 준비 10회·측정 30회 및 준비 상태/SQL 캡처 요청의 모든 응답을 seed 기대값과 검증했다. 두 분포 모두 totalCount=990이며 상품 20개와 순서가 같고 핫스팟 상품의 좋아요 수만 다르다.

| HTTP                 |   일반 10만 건 | 핫스팟 10만 건 |
| -------------------- | -------------: | -------------: |
| 중앙값               |        14.51ms |        22.20ms |
| p95                  |        17.89ms |        27.10ms |
| 업무 SELECT          |              5 |              5 |
| 상품 페이지 읽기     | 20행·Sort 없음 |           동일 |
| 전체 건수 COUNT 읽기 |          990행 |           동일 |
| 좋아요 읽기          |           93행 |       50,093행 |

HTTP는 연결·전체 본문 읽기를 포함한 urllib 시간이다. 별도 JVM의 로컬 순차 표본이므로 두 HTTP 시간 차이 전체를 쏠림의 인과 효과로 단정하지 않는다. 같은 연결에서 순서를 교대한 위 SQL 비교와 구분한다. 목표 응답 시간/SLO와 동시 부하가 정해지지 않아 운영 성능 합격으로 판정하지 않는다. SQL 수집은 시간 측정 뒤 general log로 수행했고 기존 로그 설정을 복원했다. JOIN 후보·캐시는 추가하지 않았다.

### 실행·정리·문서 검증

첫 SQL 실험은 datetime 소수초 출력 형식 차이로 기대값 검증에서 중단됐고 복제 테이블은 finally에서 정리됐다. 날짜 출력 형식을 정규화한 성공 실행만 결과에 포함했다. 두 실행기는 기존 결과 덮어쓰기를 거부한다. 실험 후 원본 데이터·인덱스 정의 일치, 임시 테이블 제거, 직접 시작한 API 종료를 확인했다.

bootJar 성공, Checkstyle main/test 성공(UP-TO-DATE), LayeredArchitectureTest 1건 성공(실패·오류·skip 0). 이번에는 운영 Java 코드를 수정하지 않아 전체 기능 회귀를 재실행하지 않았다. seed 기반 SQL·HTTP 검증과 관련 아키텍처 검사를 수행했다. ADR 0002의 관계 집계와 ADR 0016의 삭제 대상 ID·PK 잠금 순서를 대조했고 정책 변경은 없다. design의 비정규화·잦은 무효화에 관한 추정을 미확정 후보로 정정했다. 커밋·push는 하지 않았다.

<a id="sql-reading-guide"></a>

## 14. 직접 실행용 SQL 정리

각 SQL은 대상 DB·기록 링크와 쿼리별 확인 목적 한 줄을 둔다. 긴 해설·실행 계획 출력·대화 메모는 아래 접힌 원본에 보존했다. 수치의 검증 근거는 앞 절의 측정 JSON이며, 사용자가 붙여 둔 출력은 별도 미검증 메모다. 특히 정리 전 18번의 인덱스 허용 쿼리 아래 출력은 직전 제외 쿼리와 같아 복사 중복 가능성이 있으므로 새로운 실측으로 간주하지 않는다.
01~03은 기존 브랜드 인덱스만 있을 때의 기준선이다. 현재 최종 인덱스 상태에서 추가 전후를 간단히 보려면 18번을 사용한다. 04·05·06·07·09·11은 준비/정리 DDL이 있으므로 블록별로 실행하고 기존 후보가 있으면 CREATE를 생략한다. 06의 주석 속 준비·정리 SQL은 선택 실행할 수 있는 블록으로 꺼냈다. 05의 마지막 DROP은 재실험 초기화용이며 평소에는 실행하지 않는다. 12번 커서 값은 1만 건 seed의 60번째 상품 값이다.
Handler는 같은 연결에서 SHOW → 일반 SELECT → SHOW 전체를 실행해 차분을 구한다. 확장의 자동 COUNT가 섞이면 SELECT 자체의 측정이 아니므로 05/07 handler-only 파일을 터미널 mysql에서 실행한다. EXPLAIN ANALYZE는 실제 조회를 실행하므로 Handler 측정 사이에 넣지 않는다. 17번은 다른 페이지 비교이며, 같은 페이지의 쏠림 전후는 18번이다.

<details>
<summary>week4-01-brand-filter.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 첫 변경 재검토: 이 단계에서는 ORDER BY·LIMIT을 SQL에 넣지 않는다.
-- 추가했던 두 인덱스를 삭제한 측정용 DB의 원래 인덱스 상태에서 실행한다.
-- 실행 계획부터 확인한다.
-- 브랜드 7의 삭제되지 않은 상품을 조회한다.
EXPLAIN SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL;

EXPLAIN SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL AND p.brand_id = 7;

-- 실제 실행 계획은 별도로 실행한다. 이것도 쿼리를 실행하므로 Handler가 증가한다.
EXPLAIN ANALYZE SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL;

EXPLAIN ANALYZE SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL AND p.brand_id = 7;

-- Handler: 반드시 같은 연결에서 일반 SELECT 하나의 직전·직후를 비교한다.
-- 아래 두 블록은 각각 실행한다. 전후 사이에 다른 쿼리를 실행하지 않는다.
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT p.* FROM product p
WHERE p.deleted_at IS NULL;
SHOW SESSION STATUS LIKE 'Handler_read%';

SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT p.* FROM product p
WHERE p.deleted_at IS NULL AND p.brand_id = 7;
SHOW SESSION STATUS LIKE 'Handler_read%';




지금 난 브랜드 7의 상품을 가격순으로 조회한다. 인거고, 브랜드 7만 조건을 걸도록 db를 수정해씅니까 난 지금 이 코드부터 이제 조회속도를 높인다고 보면되겟지?
기존에 db로 옮긴건 사실 시작은 아니고, 이제 옮겻으니까 이제부터 시작! 이게 정리가 안되니까 다음에 뭐여야되는지 자꾸 헷갈려. 그럼 내가 하나씩 얘기해볼게. 너가 의견주고 해줘. 이제 너가 말한다고 브랜드 7의 상품이 조회되었으니까 가격 순으로 order by를 추가해야돼.

지금부터의 출발점을 “DB에서 브랜드 7의 미삭제 상품을 조회하는 코드”로 잡자. 그전에 브랜드 조건을 DB로 옮긴 과정은 준비 단계로 두면 덜 헷갈려.
최종 목표는 브랜드 7의 상품을 가격순으로 20개 조회하는 것이고, 지금 다시 따라가는 첫 SQL에는 가격 정렬이 아직 없어.
네가 말한 다음 단계가 맞아. 기존 조건에 이것만 추가하면 돼.
ORDER BY price ASC, created_at DESC, id DESC
이번 단계에서는 99개를 모두 반환하되 DB가 가격순으로 정렬하게 하는 것만 확인하자. 가격이 같을 때 순서가 일정하도록 생성 시각·ID도 넣는 거야.
```

</details>

<details>
<summary>week4-02-price-sort.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 출발점: 브랜드 7의 미삭제 상품 조회. 이번 변경은 ORDER BY만 추가한다.
-- LIMIT·새 인덱스·좋아요 조회는 다음 단계에서 검토한다.
SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL
  AND p.brand_id = 7
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;

EXPLAIN
SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL
  AND p.brand_id = 7
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;

EXPLAIN ANALYZE
SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL
  AND p.brand_id = 7
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;

-- 아래 블록은 같은 연결에서 순서대로 실행하고 카운터의 전후 차이를 계산한다.
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL
  AND p.brand_id = 7
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;
SHOW SESSION STATUS LIKE 'Handler_read%';
```

</details>

<details>
<summary>week4-03-fixed-product-page.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 이후 인덱스 비교에서 고정할 쿼리. 기존 브랜드·삭제 인덱스는 유지한다.
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

/* 직접 확인한 실행 계획 (실행할 SQL이 아니므로 주석으로 보존)
-> Limit: 20 row(s)  (cost=34.6 rows=20) (actual time=1.63..1.63 rows=20 loops=1)
    -> Sort: product.price, product.created_at DESC, product.id DESC, limit input to 20 row(s) per chunk  (cost=34.6 rows=99) (actual time=1.63..1.63 rows=20 loops=1)
        -> Index lookup on product using idx_product_brand_deleted (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=34.6 rows=99) (actual time=0.464..1.49 rows=99 loops=1)
*/


-- Handler_read_*: MySQL 서버가 스토리지 엔진에 요청한 읽기 작업의 누적 카운터.
-- 아래 일반 SELECT 하나의 직후 값 - 직전 값을 계산한다.
-- 같은 연결에서 실행하고, 두 SHOW 사이에 다른 쿼리·EXPLAIN ANALYZE를 실행하지 않는다.
-- Handler_read_key      : 인덱스 키로 찾는 읽기 요청.
-- Handler_read_next     : 인덱스 순서에서 다음 항목을 읽는 요청.
-- Handler_read_prev     : 인덱스 순서에서 이전 항목을 읽는 요청.
-- Handler_read_first    : 인덱스의 첫 항목을 읽는 요청.
-- Handler_read_last     : 인덱스의 마지막 항목을 읽는 요청.
-- Handler_read_rnd      : 지정한 위치로 행을 읽는 요청.
-- Handler_read_rnd_next : 테이블 스캔 등에서 다음 행을 읽는 요청.
-- 디스크 읽기·Root/Branch/Leaf 페이지 방문 횟수·정렬 비교 횟수는 아니다.
-- 보조 인덱스에서 기본키로 실제 행을 가져오는 InnoDB 내부 작업도 별도 횟수로 드러나지 않는다.
-- 확장이 실행마다 다른 연결을 쓰면 이 전후 비교는 유효하지 않다.
--
-- 이번 측정: read_key 증가 1, read_next 증가 99, 나머지 증가 0.
-- 직전 단계(ORDER BY만 있고 LIMIT 없음)도 key 1·next 99였다. 같아도 정상이다.
-- 기존 인덱스는 가격순이 아니므로 가장 저렴한 20개를 고르려면 후보 99개를 읽어야 한다.
-- LIMIT은 반환 상품을 99개 → 20개로 줄였지만 읽는 후보는 아직 줄이지 못했다.
-- 정렬 유무·반환 수는 ANALYZE의 Sort·Limit 노드에서 확인한다.
--
-- 앞으로의 비교: 위의 고정 쿼리를 그대로 두고 가격 정렬용 인덱스를 추가했을 때
-- ANALYZE의 실제 읽는 행·Sort 유무와 Handler 증가량이 함께 줄어드는지 확인한다.
-- key 요청 수가 같아도 next 요청 수가 줄 수 있다. 정확한 값은 실제 실행으로 기록한다.
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 기존 인덱스의 같은 브랜드·삭제 상태 구간은 ID 순서다. 가격 순서를 비교한다.
-- 이 보조 확인 쿼리만 힌트를 사용한다. 실제 측정 쿼리는 힌트를 사용하지 않는다.
SELECT brand_id, deleted_at, id, price
FROM product FORCE INDEX (idx_product_brand_deleted)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY id
LIMIT 10;
```

</details>

<details>
<summary>week4-04-price-tie-index.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 순서: 03 기존 브랜드 인덱스 → 04 가격까지만 → 05 동점 정렬까지 포함.
-- price까지 인덱스에 추가하는 경우 04번을 해보고 왜 05번처럼 인덱스 만들어야하는지 확인하려한다.
CREATE INDEX idx_product_brand_price_only ON product (brand_id, deleted_at, price);
ANALYZE TABLE product;

--force index없이 실행하니까 새로 추가한 idx_product_brand_price_only가 아니라 idx_product_brand_deleted 이 인덱스를 탄다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

--실행계획
-> Limit: 20 row(s)  (cost=34.6 rows=20) (actual time=0.957..0.961 rows=20 loops=1)
    -> Sort: product.price, product.created_at DESC, product.id DESC, limit input to 20 row(s) per chunk  (cost=34.6 rows=99) (actual time=0.954..0.956 rows=20 loops=1)
        -> Index lookup on product using idx_product_brand_deleted (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=34.6 rows=99) (actual time=0.406..0.846 rows=99 loops=1)


SHOW INDEX FROM product;

--fore index로 idx_product_brand_price_only 타게 만든다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price_only)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

--실행계획
-> Limit: 20 row(s)  (cost=34.6 rows=20) (actual time=0.232..0.236 rows=20 loops=1)
    -> Sort: product.price, product.created_at DESC, product.id DESC, limit input to 20 row(s) per chunk  (cost=34.6 rows=99) (actual time=0.231..0.233 rows=20 loops=1)
        -> Index lookup on product using idx_product_brand_price_only (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=34.6 rows=99) (actual time=0.0468..0.193 rows=99 loops=1)


EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- Handler는 각 후보의 일반 SELECT 직전·직후를 같은 연결에서 비교한다.
-- 가격까지만: key 1·next 99, Index lookup 99행·Sort 있음.
-- 전체 정렬: key 1·next 19, Index lookup 20행·Sort 없음.
-- 모두 같은 20개 반환. WHERE·ORDER BY·SELECT 컬럼·LIMIT은 동일하다.
-- (brand_id, deleted_at, price)는 같은 가격 안에서 생성 시각 DESC·ID DESC까지 맞추지 못한다.
-- 기본키 id가 자동 포함되어도 요구한 동점 순서를 모두 해결한다는 뜻은 아니다.
--
-- 옵티마이저는 두 후보를 모두 실행해 시간을 재는 것이 아니라,
-- 통계·인덱스 구조·조건·필요 컬럼·정렬 등을 바탕으로 실행 전 비용을 추정한다.
-- 현재 데이터의 optimizer trace에서 두 ref 후보는 예상 rows=99·cost=34.65로 같았다.
-- 힌트 없는 SQL은 기존 idx_product_brand_deleted를 선택했다.
-- 기존 인덱스라서 항상 우선한다는 규칙이나 동률의 정확한 내부 선택 기준은 확인하지 않았다.
-- cost는 밀리초가 아니다. 예상이 실제와 다를 수 있어 ANALYZE·Handler·시간을 함께 확인한다.
-- 이번 쿼리에서 이득이 작다는 뜻이지 가격 범위 등 다른 쿼리에서도 불필요하다는 뜻은 아니다.

-- 재실험 후 이번에 생성한 짧은 후보만 정리한다.
-- DROP INDEX idx_product_brand_price_only ON product;

-- 99개 후보를 끝까지 읽을 때: 첫 항목은 read_key, 이후 98개 + 끝 확인은 read_next 99로 관찰됐다.
-- 05는 LIMIT을 채우고 멈추므로 다음 19개만 읽고 끝 확인은 필요하지 않았다.
-- 아래 값들은 이번 측정의 관찰값이며 모든 계획에 고정된 공식은 아니다.
```

</details>

<details>
<summary>week4-05-handler-only.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 터미널의 MySQL 클라이언트로 이 파일 전체를 실행한다.
-- 한 연결에서 실행하며 확장의 자동 COUNT가 들어오지 않는다.
SELECT CONNECTION_ID() AS measurement_connection;

SELECT 'BEFORE: 실행 전 누적값' AS measurement;
SHOW SESSION STATUS LIKE 'Handler_read%';

SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

SELECT 'AFTER: 실행 후 누적값' AS measurement;
SHOW SESSION STATUS LIKE 'Handler_read%';

-- AFTER - BEFORE로 계산한다. 현재 전체 정렬 인덱스에서는 key +1, next +19를 관찰했다.
-- 실제 값은 현재 인덱스·실행 계획과 함께 해석한다. ANALYZE는 이 구간에 넣지 않는다.
```

</details>

<details>
<summary>week4-05-price-index.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 순서: 04 가격까지만 있는 후보를 확인한 뒤 05 전체 정렬 인덱스로 비교한다.
-- 05 재실험 순서: 먼저 SHOW INDEX FROM product로 인덱스 존재 여부를 확인한다.
-- idx_product_brand_price가 없을 때만 아래 CREATE INDEX를 실행한다.
-- 04의 짧은 후보와 하나씩 비교하려면 먼저 짧은 후보만 삭제한다.
-- DROP INDEX idx_product_brand_price_only ON product;
-- 기존 idx_product_brand_deleted는 유지한다.
-- 파일 전체 실행 대신 DDL·조회·Handler 블록을 나눠 선택 실행한다.
-- 맨 아래 DROP은 04로 다시 넘어갈 때만 실행한다.
CREATE INDEX idx_product_brand_price
ON product (brand_id, deleted_at, price ASC, created_at DESC, id DESC);
ANALYZE TABLE product;

-- 쿼리는 직전 단계와 같다. 실제 측정에는 인덱스 힌트를 넣지 않는다.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

/* 직접 실행한 계획 기록
-> Limit: 20 row(s)  (cost=34.6 rows=20) (actual time=0.425..0.564 rows=20 loops=1)
    -> Index lookup on product using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=34.6 rows=99) (actual time=0.421..0.559 rows=20 loops=1)
*/


DROP INDEX idx_product_brand_price_only ON product;

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

/* 직접 실행한 계획 기록
-> Limit: 20 row(s)  (cost=34.6 rows=20) (actual time=0.831..0.906 rows=20 loops=1)
    -> Index lookup on product using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=34.6 rows=99) (actual time=0.696..0.77 rows=20 loops=1)
*/


-- Handler 측정 방법
-- VS Code MySQL 확장은 이 일반 SELECT 뒤에 Total 표시용 COUNT를 자동 실행했다.
-- 실제 로그: LIMIT 20 SELECT + LIMIT 없는 COUNT가 같은 연결에서 실행됨.
-- 상품 조회 key +1 / next +19, 자동 COUNT key +1 / next +99 => 합계 key +2 / next +118.
-- 따라서 확장에서 보이는 전후 차이를 상품 SELECT 하나의 읽기량으로 해석하지 않는다.
-- EXPLAIN ANALYZE는 확장에서 실행해 계획·actual rows·Sort 유무를 확인해도 된다.
-- Handler만 측정하려면 다음 명령을 저장소 루트의 터미널에서 실행한다.
-- 이 명령은 별도 파일을 한 MySQL 연결에서 실행하며 자동 COUNT를 보내지 않는다.
--
-- docker compose -f docker/infra-compose.yml --profile benchmark exec -T mysql-benchmark sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --table -u root -D loopers_benchmark' < scripts/sql/week4-05-handler-only.sql
--
-- 출력의 BEFORE·AFTER에서 같은 항목을 찾아 AFTER - BEFORE를 계산한다.
-- 실행되는 SQL은 week4-05-handler-only.sql에서 확인한다. 데이터·인덱스를 변경하지 않는다.
-- 아래 세 문장은 원리를 보여주는 블록이다. 확장에서 실행하면 자동 COUNT가 섞일 수 있다.
-- 같은 연결에서 직전·직후 차이를 계산한다. ANALYZE는 이 구간 밖에서 실행한다.
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 관찰: read_key 증가 1·read_next 증가 19.
-- 첫 항목을 키로 얻고 다음 항목 19개를 읽어 총 20개를 반환했다.
-- 기존 브랜드 인덱스에서는 key 1·next 99: 구간 전체를 확인한 뒤 정렬해야 했다.
-- 새 인덱스에서는 ANALYZE의 Index lookup 실제 rows 20, Sort 없음.
-- 이름은 인덱스에 없어 실제 상품 행 조회가 필요하다. 아직 커버링 조회는 아니다.
-- Handler는 InnoDB 내부 기본키 조회 횟수·트리 높이·디스크 접근 수를 직접 세지 않는다.

-- Leaf 키 순서를 이해하기 위한 보조 조회. 물리 페이지 덤프가 아니다.
-- 이 보조 쿼리만 특정 인덱스를 명시한다.
SELECT brand_id, deleted_at, price, created_at, id
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 6;

--삭제하고 테스트
DROP INDEX idx_product_brand_price ON product;
```

</details>

<details>
<summary>week4-06-implicit-id.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 06: 기본키 자동 포함과 id DESC 정렬을 구분한다.
-- 비교용 인덱스는 자동 실험 후 삭제했다. 직접 재실험할 때만 생성한다.
-- CREATE INDEX idx_product_brand_price_time
-- ON product (brand_id, deleted_at, price ASC, created_at DESC);
-- ANALYZE TABLE product;

-- 이 후보도 id를 기본키로 포함하지만, 요청한 id DESC와는 다르다.
-- 후보의 동작만 분리하려고 FORCE INDEX를 사용한다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price_time)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 비교: 05 인덱스는 동일 쿼리의 id DESC까지 맞춘다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 자동 포함 기본키 순서를 이해하는 보조 조회 (원래 쿼리와 ORDER BY가 다르다).
SELECT brand_id, deleted_at, price, created_at, id
FROM product FORCE INDEX (idx_product_brand_price_time)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id ASC
LIMIT 6;

-- 관찰: 같은 가격·생성 시각 안에서 id 1407 → 4407 → 7407 (ASC).
-- 원래 요청은 7407 → 4407 → 1407 (DESC).
-- id 미명시 후보: 실제 rows 99·Sort 있음, Handler key +1·next +99.
-- id DESC 후보: 실제 rows 20·Sort 없음, Handler key +1·next +19.
-- 반환 결과는 두 후보 모두 같은 20개다. 정렬 필요 여부가 다르다.
-- 인덱스 할당량은 이번 통계에서 둘 다 400KiB였다.
-- 기본키가 이미 포함되어 있으므로 id DESC 명시가 ID를 중복 저장한다고 보지 않는다.
-- 필요 여부는 쿼리의 정렬 방향에 따라 결정한다.

-- Handler는 확장의 자동 COUNT가 없는 실행기로 같은 연결 전후 차이를 확인한다.
-- 재실험 후 이번 비교용 후보만 삭제한다.
-- DROP INDEX idx_product_brand_price_time ON product;
```

</details>

<details>
<summary>week4-07-covering.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 07: 커버링은 특정 쿼리에 필요한 값을 인덱스에서 얻을 수 있는 관계다.
-- 기존 05 인덱스는 유지한다. 먼저 이름 포함·제외만 비교한다.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

--실행계획
-> Limit: 20 row(s)  (cost=34.6 rows=20) (actual time=0.133..0.167 rows=20 loops=1)
    -> Index lookup on product using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=34.6 rows=99) (actual time=0.131..0.163 rows=20 loops=1)


EXPLAIN
SELECT id, brand_id, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

EXPLAIN ANALYZE
SELECT id, brand_id, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

--실행계획
-> Limit: 20 row(s)  (cost=11.4 rows=20) (actual time=0.071..0.0934 rows=20 loops=1)
    -> Filter: (product.deleted_at is null)  (cost=11.4 rows=99) (actual time=0.068..0.0886 rows=20 loops=1)
        -> Covering index lookup on product using idx_product_brand_price (brand_id=7, deleted_at=NULL)  (cost=11.4 rows=99) (actual time=0.0652..0.0839 rows=20 loops=1)


-- 이름 포함: Using index condition, Index lookup. 이름은 실제 상품 행에서 가져온다.
-- 이름 제외: Using where; Using index, Covering index lookup.
-- 두 쿼리 모두 실제 20행·Sort 없음·Handler key +1 / next +19.
-- Handler가 같아도 커버링 여부가 같은 것은 아니다.
-- 기본키로 실제 행을 가져오는 InnoDB 내부 작업은 Handler에 별도로 나타나지 않는다.
-- Using index condition은 커버링 표시가 아니다. Using index·Covering 노드로 확인한다.
-- 이름 제외는 원리 실험이다. 실제 API에서 이름을 제거한다는 뜻은 아니다.

-- 터미널에서 Handler 확인 (확장의 자동 COUNT 제외):
-- docker compose -f docker/infra-compose.yml --profile benchmark exec -T mysql-benchmark sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --table -u root -D loopers_benchmark' < scripts/sql/week4-07-handler-only.sql

-- 대안: 이름을 인덱스에 넣으면 원래 이름 포함 쿼리도 커버링이 된다.
-- 비교용 후보는 자동 실험 후 삭제했다. 직접 재실험할 때만 생성한다.
CREATE INDEX idx_product_brand_price_name
ON product (brand_id, deleted_at, price ASC, created_at DESC, id DESC, name);
-- ANALYZE TABLE product;
-- name은 정렬 키들 뒤에 둬서 기존 ORDER BY 순서를 유지한다.
-- 후보가 있을 때만 아래 쿼리가 실행된다. 후보 분리를 위해 힌트를 사용한다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price_name)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

--실행계획
-> Limit: 20 row(s)  (cost=8.5 rows=20) (actual time=0.459..0.483 rows=20 loops=1)
    -> Filter: (product.deleted_at is null)  (cost=8.5 rows=50) (actual time=0.456..0.478 rows=20 loops=1)
        -> Covering index lookup on product using idx_product_brand_price_name (brand_id=7, deleted_at=NULL)  (cost=8.5 rows=50) (actual time=0.453..0.474 rows=20 loops=1)


-- 이름 인덱스도 20행·Sort 없음이고 원래 응답과 모든 값이 같았다.
-- 인덱스 할당량: 05 약 400KiB → 이름 포함 약 1,552KiB (이번 데이터·통계).
-- 이름 변경 시 인덱스 항목 갱신도 필요하다. 쓰기 시간은 아직 측정하지 않았다.
-- 재실험 후 후보만 제거한다.
DROP INDEX idx_product_brand_price_name ON product;
```

</details>

<details>
<summary>week4-07-handler-only.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 같은 연결에서 각 SELECT 직전·직후 차이를 따로 계산한다. 이름 인덱스가 없는 05 상태용이다.
SELECT 'WITH NAME - BEFORE' AS measurement;
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;
SELECT 'WITH NAME - AFTER' AS measurement;
SHOW SESSION STATUS LIKE 'Handler_read%';

SELECT 'WITHOUT NAME - BEFORE' AS measurement;
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT id, brand_id, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;
SELECT 'WITHOUT NAME - AFTER' AS measurement;
SHOW SESSION STATUS LIKE 'Handler_read%';
```

</details>

<details>
<summary>week4-08-index-prefix.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 08: 긴 인덱스의 앞부분으로 짧은 인덱스 역할을 대신할 수 있는가?
-- 인덱스는 삭제하지 않는다. FORCE INDEX는 경로 비교용이다.

-- 브랜드 조건만: 두 인덱스 모두 해당 99개를 찾는다.
EXPLAIN ANALYZE
SELECT p.* FROM product p FORCE INDEX (idx_product_brand_deleted)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL;
EXPLAIN ANALYZE
SELECT p.* FROM product p FORCE INDEX (idx_product_brand_price)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL;

-- 실제 API의 활성 상품 전체 건수: 두 인덱스 모두 앞부분으로 같은 범위를 집계한다.
EXPLAIN ANALYZE
SELECT COUNT(*) FROM product p FORCE INDEX (idx_product_brand_deleted)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM brand b WHERE b.id = p.brand_id AND b.deleted_at IS NULL);
EXPLAIN ANALYZE
SELECT COUNT(*) FROM product p FORCE INDEX (idx_product_brand_price)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM brand b WHERE b.id = p.brand_id AND b.deleted_at IS NULL);

-- 브랜드 삭제 대상 ID 조회: 기존 인덱스는 해당 구간에서 ID 순서, 긴 인덱스는 가격순.
EXPLAIN ANALYZE
SELECT p.id FROM product p FORCE INDEX (idx_product_brand_deleted)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL ORDER BY p.id;
EXPLAIN ANALYZE
SELECT p.id FROM product p FORCE INDEX (idx_product_brand_price)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL ORDER BY p.id;

-- 관찰: 두 경로 모두 99개를 찾고 같은 결과를 만든다.
-- ID 정렬 조회만 긴 인덱스에서 Sort가 추가됐다.
-- 긴 인덱스의 왼쪽 연속 컬럼으로 찾을 수 있어도 같은 읽기 순서를 가진 것은 아니다.
-- 삭제 연산 전체·동시성·쓰기는 이 실험에 포함하지 않았다.
-- 기존 인덱스 제거 여부는 추가 쓰기/공간 비용과 이 조회의 정렬 비용을 함께 판단한다.
-- Handler는 확장의 자동 COUNT가 없는 compare-week4-index-prefix.py로 확인했다.
```

</details>

<details>
<summary>week4-09-price-first.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- ①: 같은 열이라도 순서를 바꾸면 읽는 방식이 달라진다.
-- 임시 후보는 실험 후 삭제했다. 재실험할 경우에만 생성한다.
CREATE INDEX idx_product_price_first
ON product (price ASC, brand_id, deleted_at, created_at DESC, id DESC);
ANALYZE TABLE product;

-- 쿼리는 05와 동일하다. 가격을 맨 앞으로 옮기고 나머지 조건·동점 방향은 유지했다.
-- 이번 비교만 FORCE INDEX로 후보를 분리한다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_price_first)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

--실행계획
-> Limit: 20 row(s)  (cost=70 rows=0.2) (actual time=1.62..3.01 rows=20 loops=1)
    -> Filter: ((product.brand_id = 7) and (product.deleted_at is null))  (cost=70 rows=0.2) (actual time=1.62..3.01 rows=20 loops=1)
        -> Index scan on product using idx_product_price_first  (cost=70 rows=20) (actual time=0.499..2.87 rows=1600 loops=1)


EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

--실행계획
-> Limit: 20 row(s)  (cost=17.9 rows=20) (actual time=0.0459..0.0602 rows=20 loops=1)
    -> Index lookup on product using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=17.9 rows=51) (actual time=0.045..0.0581 rows=20 loops=1)

-- 가격 우선: 가격순으로 다른 브랜드도 함께 읽고 brand_id·deleted_at을 필터한다.
-- 관찰: Index scan 1,600행 → Filter 20행 → Limit 20행. Sort는 없다.
-- 브랜드 우선: (7, NULL) 구간을 찾아 20행만 읽는다. Sort는 없다.
-- 즉 Sort가 없다는 사실만으로 효율적이라고 판단하지 않는다.
-- Handler 차분: 가격 우선 first +1·key +1·next +1599, 브랜드 우선 key +1·next +19.
-- first·key는 페이지 방문 수가 아니다. 측정은 자동 COUNT 없는 클라이언트에서 했다.
-- 결과·순서는 같다. 읽는 범위가 다르다.
-- 이번 고정 seed에서는 1,600개였다. 브랜드 7 상품의 가격 분포가 달라지면 값도 달라진다.
-- 힌트 없는 자동 선택은 두 후보 중 기존 브랜드 우선 인덱스를 선택했다.

-- 같은 조건에서 실제 자동 선택 확인 (후보 존재 여부에 따라 possible_keys가 달라짐).
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 재실험 후 이번 후보만 제거한다.
DROP INDEX idx_product_price_first ON product;
```

</details>

<details>
<summary>week4-10-brand-in.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- ②: 브랜드 하나의 가격순과 두 브랜드를 합친 가격순은 다르다.
/*
idx_product_brand_deleted
idx_product_brand_price
이 인덱스 두개 있을 경우 테스트
*/

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=34.6 rows=20) (actual time=0.552..0.791 rows=20 loops=1)
    -> Index lookup on product using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=34.6 rows=99) (actual time=0.534..0.771 rows=20 loops=1)


EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id IN (7, 8) AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=89.6 rows=20) (actual time=0.989..0.992 rows=20 loops=1)
    -> Sort: product.price, product.created_at DESC, product.id DESC, limit input to 20 row(s) per chunk  (cost=89.6 rows=198) (actual time=0.988..0.99 rows=20 loops=1)
        -> Index range scan on product using idx_product_brand_deleted over (brand_id = 7 AND deleted_at = NULL) OR (brand_id = 8 AND deleted_at = NULL), with index condition: ((product.brand_id in (7,8)) and (product.deleted_at is null))  (cost=89.6 rows=198) (actual time=0.232..0.916 rows=198 loops=1)


-- 긴 인덱스도 확인: 각 브랜드 구간 안은 가격순이지만 두 구간을 합친 순서는 가격순이 아니다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id IN (7, 8) AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=89.6 rows=20) (actual time=0.737..0.741 rows=20 loops=1)
    -> Sort: product.price, product.created_at DESC, product.id DESC, limit input to 20 row(s) per chunk  (cost=89.6 rows=198) (actual time=0.736..0.738 rows=20 loops=1)
        -> Index range scan on product using idx_product_brand_price over (brand_id = 7 AND deleted_at = NULL) OR (brand_id = 8 AND deleted_at = NULL), with index condition: ((product.brand_id in (7,8)) and (product.deleted_at is null))  (cost=89.6 rows=198) (actual time=0.136..0.641 rows=198 loops=1)


-- 관찰: 단일 브랜드 20개 접근·Sort 없음. IN은 198개 접근·Sort 있음.
-- 힌트 없는 IN은 기존 브랜드 인덱스를 선택했다. 긴 인덱스 강제에서도 Sort가 남았다.
-- 조건 변경이므로 결과는 단일 브랜드와 다르다. 각각의 기대값을 검증했다.
-- Handler: 단일 key +1·next +19, IN key +2·next +198.
-- 자동 COUNT가 없는 클라이언트에서 전후 차이로 확인한 값이다.
```

</details>

<details>
<summary>week4-11-deleted-condition.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- ③: deleted_at이 인덱스에 없으면 얼마나 더 읽는가?
-- 실제 product는 변경하지 않는다. 이 복제 테이블은 자동 실험 후 삭제된 상태다.
-- 직접 따라갈 때 아래 준비를 선택 실행하고, 마지막에 복제 테이블만 정리한다.
-- 같은 이름의 테이블이 이미 있으면 용도를 확인하고 진행한다.
CREATE TABLE w4_product_deleted_lab LIKE product;
INSERT INTO w4_product_deleted_lab SELECT * FROM product;
CREATE INDEX idx_lab_without_deleted ON w4_product_deleted_lab
    (brand_id, price ASC, created_at DESC, id DESC);
ANALYZE TABLE w4_product_deleted_lab;

-- 원래 삭제 분포: 이번 데이터에서는 두 후보 모두 20개만 읽었다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM w4_product_deleted_lab FORCE INDEX (idx_lab_without_deleted)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=26 rows=10) (actual time=0.198..0.334 rows=20 loops=1)
    -> Filter: (w4_product_deleted_lab.deleted_at is null)  (cost=26 rows=10) (actual time=0.195..0.329 rows=20 loops=1)
        -> Index lookup on w4_product_deleted_lab using idx_lab_without_deleted (brand_id=7)  (cost=26 rows=100) (actual time=0.193..0.325 rows=20 loops=1)

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM w4_product_deleted_lab FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=17.9 rows=20) (actual time=0.137..0.164 rows=20 loops=1)
    -> Index lookup on w4_product_deleted_lab using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (w4_product_deleted_lab.deleted_at is null)  (cost=17.9 rows=51) (actual time=0.132..0.159 rows=20 loops=1)


-- 복제 테이블에서만 저가 활성 상품 70개를 삭제 상태로 만든다.
UPDATE w4_product_deleted_lab
SET deleted_at = '2026-10-14 00:00:00'
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 70;

-- 같은 두 쿼리를 다시 확인한다.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM w4_product_deleted_lab FORCE INDEX (idx_lab_without_deleted)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=26 rows=10) (actual time=0.765..0.877 rows=20 loops=1)
    -> Filter: (w4_product_deleted_lab.deleted_at is null)  (cost=26 rows=10) (actual time=0.761..0.871 rows=20 loops=1)
        -> Index lookup on w4_product_deleted_lab using idx_lab_without_deleted (brand_id=7)  (cost=26 rows=100) (actual time=0.344..0.86 rows=91 loops=1)


EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM w4_product_deleted_lab FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;
-> Limit: 20 row(s)  (cost=17.9 rows=20) (actual time=0.0497..0.0725 rows=20 loops=1)
    -> Index lookup on w4_product_deleted_lab using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (w4_product_deleted_lab.deleted_at is null)  (cost=17.9 rows=51) (actual time=0.049..0.0706 rows=20 loops=1)



-- 미포함 후보: (brand_id=7) 가격순으로 읽고 실제 행의 deleted_at을 확인한다.
-- 70개 추가 삭제 + 원래 삭제된 상품 하나 + 반환할 20개 = 실제 접근 91개.
-- 포함 후보: (7, NULL) 구간부터 읽어 20개만 접근한다.
-- 둘 다 Sort는 없다. 이번 조건에서 결과·순서는 같다.
-- Handler next 증가: 미포함 90, 포함 19.
-- 모든 순서 비교가 끝나면 이번에 만든 복제 테이블만 정리한다.
DROP TABLE w4_product_deleted_lab;
```

</details>

<details>
<summary>week4-12-offset-cursor.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- ④: 같은 05 정렬 인덱스에서 OFFSET과 커서 비교.
-- 브랜드 7의 활성 상품이 99개라 OFFSET 900 대신 60을 사용한다.

/*
idx_product_brand_deleted
idx_product_brand_price
이 인덱스 두개 있을 경우 테스트
*/


EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20 OFFSET 60;

-> Limit/Offset: 20/60 row(s)  (cost=34.6 rows=20) (actual time=0.318..0.347 rows=20 loops=1)
    -> Index lookup on product using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=34.6 rows=99) (actual time=0.16..0.34 rows=80 loops=1)


-- 이전 페이지 마지막 상품(60번째)을 확인한다. 이 준비 비용은 커서 측정에 포함하지 않는다.
SELECT price, created_at, id FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 1 OFFSET 59;
-- 고정 seed의 결과: price=66000, created_at='2026-01-28 00:00:00', id=9207.

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
  AND (
      price > 66000
      OR (price = 66000 AND created_at < '2026-01-28 00:00:00')
      OR (price = 66000 AND created_at = '2026-01-28 00:00:00' AND id < 9207)
  )
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=18.3 rows=20) (actual time=0.141..0.259 rows=20 loops=1)
    -> Index range scan on product using idx_product_brand_price over (brand_id = 7 AND deleted_at = NULL AND price = 66000 AND created_at = '2026-01-28 00:00:00.000000' AND 9207 < id) OR (brand_id = 7 AND deleted_at = NULL AND price = 66000 AND '2026-01-28 00:00:00.000000' < created_at) OR (brand_id = 7 AND deleted_at = NULL AND 66000 < price), with index condition: ((product.brand_id = 7) and (product.deleted_at is null) and ((product.price > 66000) or ((product.price = 66000) and (product.created_at < TIMESTAMP'2026-01-28 00:00:00')) or ((product.created_at = TIMESTAMP'2026-01-28 00:00:00') and (product.price = 66000) and (product.id < 9207))))  (cost=18.3 rows=39) (actual time=0.137..0.253 rows=20 loops=1)


-- 가격 ASC라 다음 가격은 >, 생성 시각·ID DESC라 다음 값은 < 조건이다.
-- 반환한 전체 컬럼·순서는 OFFSET 결과와 같다.
-- OFFSET: 실제 80개 읽고 앞의 60개 버림. Handler key +1·next +79.
-- 커서: 20개 접근. OR 범위 탐색으로 key +3·next +19 관찰. Sort는 둘 다 없다.
-- 읽기량은 줄었지만 이번 소규모 SQL 시간 중앙값은 OFFSET 0.177ms·커서 0.191ms였다.
-- 커서가 항상 빠르다고 결론 내리지 않는다. 깊이가 더 큰 경우는 별도 데이터가 필요하다.
-- 실제 사용은 이전 페이지에서 받은 커서가 있어야 한다. 임의 페이지 이동과 계약이 다르다.
```

</details>

<details>
<summary>week4-13-like-product-index.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 상품 20개의 페이지를 고른 뒤 해당 상품들의 좋아요 수를 묶음 집계한다.
-- 기존 유니크 인덱스는 (user_id, product_id)라 사용자 조건 없는 이번 집계는 전체를 훑었다.
EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    7407,4407,1407,9407,6407,3407,407,8407,5407,2407,
    7707,4707,1707,9707,6707,3707,707,8707,5707,2707
)
GROUP BY product_id;

-- 아래 DDL은 인덱스가 없는 DB에 한 번만 적용한다. 현재는 적용 완료 상태다.
-- CREATE INDEX idx_product_like_product ON product_like (product_id);
-- ANALYZE TABLE product_like;

-- 같은 EXPLAIN ANALYZE를 적용 전후 비교한다.
-- 기존: 전체 인덱스 스캔 62,756개 → 필터 248개 → 집계 18행.
-- 추가: product_id 범위 스캔 248개 → 집계 18행.
-- 20개 상품 중 좋아요 없는 2개는 SQL 그룹 결과에 없다. 앱에서 0으로 채운다.
-- InnoDB 기본키 id도 보조 인덱스에 포함돼 이 집계는 커버링이다.
-- 유니크 인덱스는 중복 방지·사용자별 관계 탐색에 필요하므로 유지한다.
-- 등록·취소는 새 인덱스도 갱신한다. 읽기 이득과 유지 비용을 함께 본다.
```

</details>

<details>
<summary>week4-14-page-first-join.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 14: 상품 페이지를 먼저 고른 뒤 브랜드 정보·좋아요 집계를 한 번에 조회한다.
-- 현재 상품 가격·좋아요 product_id 인덱스를 유지한다. 데이터·인덱스 변경 없음.
-- 실제 API 비교에서 브랜드 존재 확인과 totalCount는 별도 쿼리로 유지했다.
EXPLAIN ANALYZE
SELECT pg.id, pg.name, pg.price, pg.created_at,
       pg.brand_id, pg.brand_name, pg.brand_description,
       COUNT(l.id) AS like_count
FROM (
    SELECT p.id, p.name, p.price, p.created_at,
           b.id AS brand_id, b.name AS brand_name, b.description AS brand_description
    FROM product p
    JOIN brand b ON b.id = p.brand_id
    WHERE p.deleted_at IS NULL
      AND b.deleted_at IS NULL
      AND p.brand_id = 7
    ORDER BY p.price ASC, p.created_at DESC, p.id DESC
    LIMIT 20 OFFSET 0
) AS pg
LEFT JOIN product_like l ON l.product_id = pg.id
GROUP BY pg.id, pg.name, pg.price, pg.created_at,
         pg.brand_id, pg.brand_name, pg.brand_description
ORDER BY pg.price ASC, pg.created_at DESC, pg.id DESC;

-- 상품 페이지의 LIMIT은 JOIN 전에 적용한다. 좋아요 행에 직접 LIMIT을 걸지 않는다.
-- LEFT JOIN으로 좋아요 0개인 상품 2개도 유지한다.
-- 관찰: 상품 20행 → 좋아요 lookup 평균 12.4행 × 20회 → LEFT JOIN 250행
-- → 임시 테이블 집계 20행 → 최종 Sort 20행.
-- 상품 후보의 Sort는 가격 인덱스로 없어졌지만 JOIN 후 최종 Sort는 남는다.
-- 5 SELECT → 3 SELECT (브랜드 확인, COUNT, 위 JOIN).
-- 실험 HTTP 중앙값: 별도 집계 16.35ms, JOIN 10.60ms. 방식별 30회, 같은 서버에서 순서 교대.
-- 모든 운영 조건에서 더 빠르다는 보장은 아니다.
-- JDBC 조회용 투영과 브랜드 조회 통합까지 포함한 후보 비교다.
-- 운영 코드는 변경하지 않았다. 실험 전용 구성은 scripts/experiments/ 아래에 있다.

-- Handler 보완 실측 (자동 COUNT 없는 SQL 클라이언트, 같은 연결 전후 차분):
-- 현재 실제 SQL 상품 페이지+좋아요 2 SELECT: key +22·next +267·rnd_next +0.
-- 위 JOIN 1 SELECT: key +272·next +267·rnd_next +42.
-- 임시 집계·파생 테이블 요청도 포함되므로 key 272를 상품 272개로 해석하지 않는다.
-- 3회 반복값이 같았다. next가 줄었다는 결과가 아니라 내부 작업의 차이를 관찰한 것이다.
-- HTTP의 5→3 왕복 감소와 이 SQL 카운터는 별도 근거다. JOIN 채택은 후속 검토로 둔다.
-- 전체 결과·측정 원본 링크: docs/week4/handler-results.md.
```

</details>

<details>
<summary>week4-15-100k-scale-check.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 목적: 05(idx_product_brand_price, week4-05-price-index.sql)에서 확인한 실행 계획이
-- 브랜드당 상품 수가 10배(약 100개 -> 약 1,000개)로 늘어난 loopers_benchmark_100k에서도
-- 같은 인덱스·같은 계획을 쓰는지 확인한다. 쿼리 자체는 05와 동일하다.
-- 이 파일은 loopers_benchmark_100k를 대상으로 실행한다. DB 이름을 꼭 확인한다.

-- 0단계: 통계 상태를 먼저 확인한다. 적재 직후라 추정치가 실제와 다를 수 있다(TABLE_ROWS 등).
SHOW TABLE STATUS LIKE 'product';

-- 1단계: 통계 갱신 전 실행 계획.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 05에서 10k 기준 기록: cost=34.6 rows=99 (index lookup, Sort 없음)
-- 여기 actual rows·cost를 10k 기록과 나란히 적어 비교한다.


-- 2단계: ANALYZE TABLE 실행 후 다시 확인한다. 데이터는 바꾸지 않는다.
ANALYZE TABLE product;

EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- key가 idx_product_brand_price가 아니거나 Extra에 Using filesort/Using temporary가 보이면
-- 10k 때와 계획이 달라진 것이다. 그 경우만 추가로 들여다본다.


-- 3단계: Handler 카운터로 실제 읽은 레코드 수를 비교한다.
-- VS Code 확장은 SELECT 뒤에 자동으로 COUNT를 붙여 보내는 경우가 있어
-- (05 파일의 49~53번째 줄 설명과 동일한 이유로) 이 블록은 터미널에서 실행하는 걸 추천한다.
--
-- docker compose -f docker/infra-compose.yml --profile benchmark exec -T mysql-benchmark \
--   sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --table -u root -D loopers_benchmark_100k' \
--   < scripts/sql/week4-15-100k-scale-check.sql
--
-- 확장에서 직접 돌리고 싶으면 아래 두 SHOW 사이의 SELECT 하나만 선택해서 실행한다.
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 05의 10k 기록: read_key +1, read_next +19 (LIMIT 20이라 딱 20개만 읽음, Sort 없음).
-- 100k에서도 브랜드당 상품 수가 10배 늘었을 뿐 LIMIT 20·인덱스 순서 접근은 그대로이므로
-- read_next가 여전히 19 근처면 "인덱스가 LIMIT만큼만 읽는다"는 05의 결론이 스케일에서도 유지된 것이다.
-- 만약 read_next가 브랜드당 상품 수(~1,000)에 가깝게 뛰면, 그 인덱스를 안 쓰고
-- 브랜드 구간 전체를 훑어 정렬했다는 뜻이니 EXPLAIN의 key·Extra를 다시 확인한다.
```

</details>

<details>
<summary>week4-16-100k-like-join-check.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 목적: 13(idx_product_like_product, week4-13-like-product-index.sql)에서 확인한
-- "범위 스캔으로 줄었다"는 결론이, product_like 행 수가 10배(62,756 -> 약 628,186)로
-- 늘어난 loopers_benchmark_100k에서도 유지되는지 확인한다.
-- 이 20개 상품 ID는 15번 파일(week4-15-100k-scale-check.sql)에서 브랜드 7을
-- price ASC로 조회했을 때 실제로 나온 100k 페이지 1의 ID다. 10k 때 쓰던 ID(7407 등)를
-- 그대로 쓰지 않는다 — 브랜드당 상품 수가 달라져 같은 ID가 같은 페이지에 있지 않다.
-- 이 파일은 loopers_benchmark_100k를 대상으로 실행한다.

-- 0단계: product_like 전체 행 수 확인 (10k 기록: 62,756 -> 전체 스캔 비용의 기준값).
SELECT COUNT(*) FROM product_like;

-- 1단계: 13과 동일한 쿼리. 상품 ID만 100k 페이지 1 기준으로 바꿨다.
EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-- 13의 10k 기록: idx_product_like_product 적용 후 전체 62,756개 스캔 -> product_id 범위
-- 스캔 248개로 줄었다(20개 상품 x 평균 like 수). 아래에서 비교할 것:
-- - key가 idx_product_like_product인지 (PRIMARY나 full scan으로 바뀌면 다른 계획)
-- - EXPLAIN ANALYZE의 실제 rows 합이 "상품 수 x 10배 scaled like 수" 근처인지
--   (브랜드·상품 수는 그대로 10배했으니 상품당 좋아요 평균도 그대로이면 248 근처가 맞다)
-- - Extra에 Using index(커버링)가 유지되는지


-- 2단계: 통계 갱신 후에도 같은 계획인지 확인.
ANALYZE TABLE product_like;

EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;


-- 3단계: Handler 카운터로 실제 읽은 레코드 수 비교 (앱에서 보는 단일 호출 기준).
-- 05 때처럼 VS Code 확장의 자동 COUNT가 섞일 수 있으니 터미널에서 실행하는 걸 추천한다.
--
-- docker compose -f docker/infra-compose.yml --profile benchmark exec -T mysql-benchmark \
--   sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --table -u root -D loopers_benchmark_100k' \
--   < scripts/sql/week4-16-100k-like-join-check.sql
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;
SHOW SESSION STATUS LIKE 'Handler_read%';

-- read_key·read_next 합이 "전체 628,186개를 훑은 흔적"이면(수만 단위로 뜀) 범위 스캔이
-- 아니라 전체 스캔으로 바뀐 것이다. 20개 상품의 좋아요 합(수백 단위) 근처면 13의 결론이
-- 10배 스케일에서도 유지된 것이다.

-- 범위 한정: 이 결론은 좋아요가 상품마다 고르게 분포된 경우에만 해당한다. 같은 인덱스를
-- 써도 좋아요가 한 상품에 쏠리면 비용이 달라진다 — 그 경우는
-- week4-17-100k-hotspot.sql(별도 복제 DB loopers_benchmark_100k_hotspot)에서 확인한다.
```

</details>

<details>
<summary>week4-17-100k-hotspot.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 목적: week4 과제의 "선택 심화 — 데이터 쏠림" 항목 확인.
-- 13(idx_product_like_product)의 "범위 스캔으로 줄었다"는 결론이, 좋아요가 한 상품에만
-- 몰렸을 때도 유지되는지 본다. 캐시는 아직 붙이지 않은 순수 SQL/인덱스 단계의 확인이다.
--
-- 대상 DB: loopers_benchmark_100k_hotspot (loopers_benchmark_100k를 복제한 별도 DB).
-- 원본 100k DB는 건드리지 않는다. 좋아요 쏠림 데이터는 scripts/seed-week4-100k-hotspot.py로 넣었다:
--   - 기존 유저 200명 + 신규 유저 50,000명(id 201~50200)
--   - 상품 97407(브랜드 7, 가격 6000, 1페이지 1번째)에만 신규 유저 5만 명이 전부 좋아요
--   - product_like: 628,186 -> 678,186행, 그중 97407이 50,002개(기존 2 + 신규 50,000)
--
-- 비교 대상:
--   핫스팟 페이지 = 브랜드 7의 1페이지(97407 포함, 가격순 상위 20개)
--   일반 페이지   = 브랜드 7의 2페이지(97407 미포함, 가격순 21~40번째) — 같은 브랜드·가격대라 조건은 최대한 맞췄다.

-- loopers_benchmark_100k와 _hotspot 둘 다 연결돼 있으면 어느 쪽이 활성 연결인지에 따라
-- 결과가 달라진다. 아래 USE로 대상을 명시해 혼동을 없앤다.
USE loopers_benchmark_100k_hotspot;

-- 1단계: 실행 계획 비교.
EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;
-- 핫스팟 페이지. 97407 하나가 50,002개라 Group aggregate의 실제 rows가 크게 뛰는지 본다.

-> Group aggregate: count(product_like.id)  (cost=30407 rows=91546) (actual time=0.0565..15.4 rows=18 loops=1)
    -> Filter: (product_like.product_id in (97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,67407,64407,61407,58407,55407,52407,49407,46407,43407,40407))  (cost=20288 rows=101193) (actual time=0.0478..13.1 rows=50093 loops=1)
        -> Covering index range scan on product_like using idx_product_like_product over (product_id = 40407) OR (product_id = 43407) OR (18 more)  (cost=20288 rows=101193) (actual time=0.0461..9.29 rows=50093 loops=1)


EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,
    4407,1407,99407,96407,93407,90407,87407,84407,81407,78407
)
GROUP BY product_id;
-- 일반 페이지. 97407이 없으니 모든 상품이 비슷하게 적은 좋아요 수를 가진다.

-> Group aggregate: count(product_like.id)  (cost=77.2 rows=256) (actual time=0.0618..0.243 rows=19 loops=1)
    -> Filter: (product_like.product_id in (37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,4407,1407,99407,96407,93407,90407,87407,84407,81407,78407))  (cost=51.6 rows=256) (actual time=0.0277..0.219 rows=255 loops=1)
        -> Covering index range scan on product_like using idx_product_like_product over (product_id = 1407) OR (product_id = 4407) OR (18 more)  (cost=51.6 rows=256) (actual time=0.0259..0.19 rows=255 loops=1)


-- 2단계: 서버가 실제로 쓴 시간을 같은 연결에서 비교한다 (SHOW PROFILES, deprecated지만 동작함).
SET profiling = 1;

SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
) GROUP BY product_id;

SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,
    4407,1407,99407,96407,93407,90407,87407,84407,81407,78407
) GROUP BY product_id;

SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
) GROUP BY product_id;

SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,
    4407,1407,99407,96407,93407,90407,87407,84407,81407,78407
) GROUP BY product_id;

SHOW PROFILES;
-- 홀수 번째(1,3)가 핫스팟 페이지, 짝수 번째(2,4)가 일반 페이지다.
-- Duration을 비교해서 핫스팟 페이지가 얼마나 더 오래 걸리는지 본다.


-- 3단계: Handler 카운터로 실제 읽은 행 수 비교 (연결 하나에서 전/후만 비교).
-- docker compose -f docker/infra-compose.yml --profile benchmark exec -T mysql-benchmark \
--   sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --table -u root -D loopers_benchmark_100k_hotspot' \
--   < scripts/sql/week4-17-100k-hotspot.sql
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
) GROUP BY product_id;
SHOW SESSION STATUS LIKE 'Handler_read%';
-- 핫스팟 페이지의 read_next 증가분. idx_product_like_product가 커버링 범위 스캔을 유지한다면
-- 이 증가분은 "20개 상품의 좋아요 총합"(약 50,100개) 근처여야 한다 — 97407 혼자서 그만큼을 차지한다.

SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,
    4407,1407,99407,96407,93407,90407,87407,84407,81407,78407
) GROUP BY product_id;
SHOW SESSION STATUS LIKE 'Handler_read%';
-- 일반 페이지의 read_next 증가분. 20개 상품의 좋아요 총합(수십~백 단위) 근처여야 한다.

-- 결론 기준: 계획(key)이 둘 다 idx_product_like_product 범위 스캔으로 같다면, 인덱스 자체는
-- 안 깨진 것이다. 다만 읽는 "양"은 핫스팟 상품의 좋아요 수에 비례해서 커지므로, 시간도 그만큼
-- 늘어난다 — 이게 "인덱스가 있어도 쏠린 상품은 그 상품의 몫만큼 비싸다"는 결론이다.
```

</details>

<details>
<summary>week4-18-100k-before-after.sql — 정리 전 설명·사용자 메모 원본</summary>

```sql
-- 10만 건 핵심 비교를 직접 확인하는 읽기 전용 보조 SQL.
-- 자동 측정 원본: docs/week4/measurements/100k-read-write-skew.json
-- 자동 측정은 추가 인덱스가 실제로 없는 복제 테이블과 있는 복제를 비교했다.
-- 여기서는 원본 인덱스를 삭제하지 않고 IGNORE INDEX로 해당 읽기 후보만 제외한다.
-- IGNORE INDEX는 쓰기 유지 비용을 없애지 않으므로 쓰기 비교에 쓰지 않는다.
USE loopers_benchmark_100k;

EXPLAIN ANALYZE
SELECT id,brand_id,name,price,created_at
FROM product IGNORE INDEX (idx_product_brand_price)
WHERE brand_id=7 AND deleted_at IS NULL
ORDER BY price ASC,created_at DESC,id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=346 rows=20) (actual time=7.89..7.89 rows=20 loops=1)
    -> Sort: product.price, product.created_at DESC, product.id DESC, limit input to 20 row(s) per chunk  (cost=346 rows=990) (actual time=7.88..7.89 rows=20 loops=1)
        -> Index lookup on product using idx_product_brand_deleted (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=346 rows=990) (actual time=2.6..7.68 rows=990 loops=1)


EXPLAIN ANALYZE
SELECT id,brand_id,name,price,created_at
FROM product
WHERE brand_id=7 AND deleted_at IS NULL
ORDER BY price ASC,created_at DESC,id DESC LIMIT 20;

-> Limit: 20 row(s)  (cost=346 rows=20) (actual time=0.365..0.371 rows=20 loops=1)
    -> Index lookup on product using idx_product_brand_price (brand_id=7, deleted_at=NULL), with index condition: (product.deleted_at is null)  (cost=346 rows=990) (actual time=0.364..0.369 rows=20 loops=1)


EXPLAIN ANALYZE
SELECT product_id,COUNT(id)
FROM product_like IGNORE INDEX (idx_product_like_product)
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-> Table scan on <temporary>  (actual time=126..126 rows=18 loops=1)
    -> Aggregate using temporary table  (actual time=126..126 rows=18 loops=1)
        -> Filter: (product_like.product_id in (97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,67407,64407,61407,58407,55407,52407,49407,46407,43407,40407))  (cost=63391 rows=312928) (actual time=70.3..125 rows=93 loops=1)
            -> Covering index scan on product_like using uk_product_like_user_product  (cost=63391 rows=625856) (actual time=1.03..96.9 rows=628186 loops=1)


EXPLAIN ANALYZE
SELECT product_id,COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-> Table scan on <temporary>  (actual time=126..126 rows=18 loops=1)
    -> Aggregate using temporary table  (actual time=126..126 rows=18 loops=1)
        -> Filter: (product_like.product_id in (97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,67407,64407,61407,58407,55407,52407,49407,46407,43407,40407))  (cost=63391 rows=312928) (actual time=70.3..125 rows=93 loops=1)
            -> Covering index scan on product_like using uk_product_like_user_product  (cost=63391 rows=625856) (actual time=1.03..96.9 rows=628186 loops=1)


-- 동일한 상품 20개로 일반 1페이지와 hotspot 1페이지를 비교한다.
-- 위 마지막 쿼리와 아래 쿼리의 ID가 같고 97407의 좋아요만 5만 개 늘었다.
EXPLAIN ANALYZE
SELECT product_id,COUNT(id)
FROM loopers_benchmark_100k_hotspot.product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-> Group aggregate: count(loopers_benchmark_100k_hotspot.product_like.id)  (cost=30541 rows=91546) (actual time=0.0497..21.5 rows=18 loops=1)
    -> Filter: (loopers_benchmark_100k_hotspot.product_like.product_id in (97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,67407,64407,61407,58407,55407,52407,49407,46407,43407,40407))  (cost=20421 rows=101193) (actual time=0.0368..18.2 rows=50093 loops=1)
        -> Covering index range scan on product_like using idx_product_like_product over (product_id = 40407) OR (product_id = 43407) OR (18 more)  (cost=20421 rows=101193) (actual time=0.0345..13.3 rows=50093 loops=1)


-- 페이지는 20행으로 끝나도 totalCount는 브랜드 후보 전체를 읽는다.
EXPLAIN ANALYZE
SELECT COUNT(id) FROM product WHERE brand_id=7 AND deleted_at IS NULL;
```

</details>

<a id="counter-experiment"></a>

## 15. 좋아요 카운터 비정규화 후보와 동시 부하 비교

[실험 코드](../../scripts/experiments/java/com/loopers/experiment/LikeCounterExperimentTest.java), [회차별 측정 원본 · 100k-counter-experiment.json](measurements/like-counter-results.json), [직접 확인 SQL 19](../../scripts/sql/week4-19-like-counter.sql).

### 가설과 구현 범위

13절에서 확인한 hotspot의 50,093행 집계 읽기를 상품별 카운터 20행 조회로 줄일 수 있는지 확인했다. 현재 운영 LikeService/LikeRepository/LikeFacade와 HTTP 계약은 바꾸지 않았다. 실험용 일반 JDBC 코드이며 init script를 지정한 테스트에서만 소스에 포함한다. 두 모드 모두 같은 product_id 인덱스를 가진 관계 복제 테이블을 사용한다. 후보만 별도의 `(product_id PK, like_count CHECK >= 0)` 테이블을 추가한다. 상품 행을 갱신하지 않아 재고·가격 변경과 같은 행의 잠금을 공유하지 않는 후보를 택했다.

후보 쓰기는 관계 INSERT 성공 건수만큼 +1, DELETE 실제 영향 건수만큼 -1 한다. 기존과 같이 특정 유니크 제약의 1062만 중복 등록으로 처리한다. 관계와 카운터 UPDATE는 같은 JDBC 트랜잭션에서 commit하며 SELECT FOR UPDATE로 값을 읽어 계산하지 않고 `like_count=like_count+delta`로 갱신한다. 카운터 없음·음수로 내려가는 상태는 성공으로 감추지 않고 rollback한다. 별도 카운터여도 같은 상품의 쓰기끼리는 같은 카운터 행을 갱신한다.

초기 채움은 쓰기가 없는 복제에만 수행했다. 실제 서비스의 상품 생성 시 0행 준비, 기존 데이터 backfill 중 동시 쓰기, 운영 전환·복구는 이번 범위가 아니다. FK 없는 복제의 합성 사용자 ID를 사용하므로 기존 사용자 검증·상품 노출 검사·컨트롤러 오류 응답을 포함한 API 실험도 아니다.

### 먼저 확인한 정확성

일반·핫스팟 각각 아래 검증을 시간 측정 전에 실행했다. 상품 100,000개의 카운터가 관계 GROUP BY와 모두 일치하는지 검증했고, 각 부하 회차가 끝난 뒤에도 전수 대조했다.

- 최초 등록 1건·중복 등록 0건, 최초 취소 1건·없는 관계 취소 0건.
- 등록/취소 각각 관계 변경 직후와 카운터 변경 직후 예외 주입: 두 변경 모두 rollback.
- 같은 관계의 동시 등록 8개는 정확히 1건만 반영, 동시 취소 8개도 정확히 1건만 반영.
- 서로 다른 사용자 8명이 같은 상품을 등록·취소하면 각각 8건 보존.
- 미커밋 등록 뒤 취소, 미커밋 취소 뒤 등록: 후행 연결이 대기하고 첫 commit 후 완료; 최종 관계와 카운터 일치.
- 일반·핫스팟 첫 페이지의 좋아요 0 포함 20개 값을 독립 seed 공식과 대조.

### 부하 조건과 측정 의미

MySQL 8.0.46, REPEATABLE READ. 같은 JVM에서 두 모드 순서를 1회차 집계→카운터, 2회차 카운터→집계로 교대했다. 각 실행은 준비 2초·측정 8초다. 읽기 전용은 4 reader, 혼합은 4 reader + 2 writer다. worker별 지속 연결을 사용하고 요청 하나가 끝나면 다음 요청을 보내는 closed-loop 방식이다. 고정 RPS나 같은 읽기/쓰기 요청 비율을 주입한 실험이 아니므로 최대 처리량·운영 SLO로 일반화하지 않는다.

읽기 시간은 JDBC 쿼리·전체 결과 소비와 결과 검증을, 쓰기는 트랜잭션 시작부터 관계 변경·후보 카운터 변경·commit·autocommit 복원 및 영향 건수 검증을 포함한다. SQL 서버 시간이나 HTTP 응답 시간이 아니다. 연결 생성·초기 backfill·EXPLAIN·정합성 전수 검사는 측정 밖이다. writer는 같은 상품 97407에 서로 다른 합성 사용자의 등록/취소를 번갈아 실행하며 종료 시 남은 관계를 제거한다.

측정 요청은 읽기 545,996건·쓰기 22,103건이다(준비·정리·정확성 검증 요청 제외). 회차별 요청 수·중앙값·p95·p99·최댓값·ops/s를 JSON에 남겼다. 아래 범위는 두 회차의 최솟값~최댓값이며 신뢰구간이나 합친 표본의 p95가 아니다. 모든 측정 요청의 실패는 0건이며 오류가 나면 실험을 실패시켜 성공 표본만 남기지 않는다. 원시 개별 지연 배열은 저장하지 않았으며 회차별 집계치를 보존했다.

| 분포   | 부하      | 방식      | 읽기 중앙값 ms |   읽기 p95 ms |        읽기 ops/s |
| ------ | --------- | --------- | -------------: | ------------: | ----------------: |
| 일반   | 읽기 전용 | 관계 집계 |    0.748~0.751 |   0.998~1.016 | 5143.500~5149.875 |
| 일반   | 읽기 전용 | 카운터    |    0.586~0.594 |   0.792~0.810 | 6372.750~6510.250 |
| 일반   | 혼합      | 관계 집계 |    0.830~0.844 |   1.214~1.266 | 4443.000~4570.375 |
| 일반   | 혼합      | 카운터    |    0.660~0.692 |   0.994~1.104 | 5294.375~5669.875 |
| 핫스팟 | 읽기 전용 | 관계 집계 |    7.498~7.604 | 10.730~10.962 |   498.000~499.375 |
| 핫스팟 | 읽기 전용 | 카운터    |    0.592~0.614 |   0.805~0.990 | 5679.500~6418.250 |
| 핫스팟 | 혼합      | 관계 집계 |    7.604~8.801 | 10.968~12.731 |   423.125~491.375 |
| 핫스팟 | 혼합      | 카운터    |    0.666~0.678 |   1.022~1.050 | 5493.375~5592.500 |

일반 분포는 집계 자체가 93행이라 이득이 작았다. 핫스팟은 50,093행 범위 집계가 카운터 PK 20행 조회로 바뀌었고 두 회차 모두 읽기 p95가 크게 줄었다. 이 결과는 카운터 후보의 검토 근거를 강화한다. HTTP 전체가 같은 배율로 개선된다는 뜻은 아니다.

### 쓰기 대가

| 핫스팟 혼합 부하     |  관계 집계 방식 |     카운터 방식 |
| -------------------- | --------------: | --------------: |
| 등록 중앙값          |   4.531~4.925ms |   6.056~6.140ms |
| 취소 중앙값          |   4.444~4.711ms |   5.913~5.937ms |
| 등록 p95             |  9.067~10.829ms |   8.981~9.090ms |
| 취소 p95             |  9.149~10.072ms |   8.802~9.126ms |
| 등록+취소 합산 ops/s | 346.125~388.750 | 309.500~313.250 |

추가 UPDATE와 같은 상품 카운터의 직렬화가 생기는 구조이며 이 실험에서 쓰기 중앙값·처리량의 비용을 관찰했다. 다만 잠금 대기 시간을 별도로 수집하지 않아 감소분 전체를 행 잠금 때문이라고 단정하지 않는다. p95는 표본 변동으로 범위가 겹치거나 역전되므로 모든 쓰기 지표가 나빠졌다고 적지 않는다. 서로 다른 상품으로 분산된 쓰기·더 많은 writer·고정 도착률은 미검증이다.

카운터 100,000행의 할당량은 두 DB 모두 4,734,976 bytes(약 4.52MiB, clustered data 포함)였다. 13절의 보조 인덱스만의 할당량과 다른 범위다.

### 판단과 다음 적용 경계

현재 증거는 **핫스팟 읽기 경로의 비정규화 후보가 유효하다**는 결론을 지지한다. 운영 도입은 상품 생성/초기 채움·등록/취소의 실제 트랜잭션 연결과 기존 HTTP 계약 회귀까지 갖춘 다음 단계로 둔다. 지금은 ADR 0002의 관계 집계·ADR 0017의 운영 쓰기 계약을 유지한다. 운영에 채택하면 이 ADR들의 결정과 책임을 함께 갱신해야 한다. 이번 실험에 적용 결정 ADR을 만들지 않은 이유다.

카운터는 캐시 miss·Redis 장애 시 DB 집계 부담을 줄일 수 있다. hit는 이미 저장된 응답을 읽으므로 이 실험의 SQL 개선 배율을 hit 개선으로 적용하지 않는다. 값 변경의 무효화·늦은 채움 문제는 별도로 남는다. 누적 좋아요 수와 쓰기 요청 빈도는 구분하며 이번 혼합 부하는 의도적으로 쓰기 쏠림을 만든 조건이다.

### 실행·정리 확인

실험 테스트와 자동 포함 ArchUnit 총 2건 통과(실패·오류·skip 0), 실험 소스를 포함한 Checkstyle test 통과. 일반·핫스팟에서 각각 자신이 생성한 관계 복제 2개와 카운터 1개를 finally에서 제거했고, 원본 상품·좋아요의 건수/합계와 전체 인덱스 정의가 전후 일치했다. 건수/합계는 보조 변경 탐지이며 암호학적 동일성 증명은 아니다. 직접 확인용 19 SQL도 실행하여 50,093행→20행 계획과 mismatch=0을 확인했다.

SQL 01~18의 기존 실행 문장 목록을 정리 전 기록과 비교해 조건·정렬·문장 순서 보존을 확인했다. 명시적 USE와 06의 주석 속 준비·정리 DDL을 실행 블록으로 꺼낸 차이만 있다. SQL에 섞인 실행 계획 출력·대화 메모는 14절에 보존했다. 운영 Java 코드는 수정하지 않았고 커밋·push는 하지 않았다.

일반 `:apps:commerce-api:check`도 BUILD SUCCESSFUL로 종료했다. XML 49개 suite·260개 테스트, 실패·오류·skip 0을 확인했고 실험 테스트는 일반 결과에 포함되지 않았다. Checkstyle main/test와 ArchUnit을 포함한다. 두 10만 건 DB에는 기존 brand/product/product_like/users 네 테이블만 남았음을 마지막으로 확인했다.

<a id="counter-production"></a>

## 16. 카운터 실제 코드 적용 — 중간 저장

사용자가 비정규화 실제 적용을 요청해 구현한 뒤, 사용량 부족으로 중간 기록을 요청했다. 아래 목록은 중단 당시 상태다. 이후 재개 기록과 17절에서 실제 API 재측정·ADR 반영·브랜드 중복 조회 제거까지 완료했다. 15절까지의 “운영 코드는 관계 집계 유지”는 당시 기록이다. 커밋·push는 하지 않았다.

### 완료한 코드

- `domain/like/LikeCountModel`: `product_like_count(product_id PK, like_count)` JPA 스키마 매핑, 0 이상 CHECK. 상품 행과 별도 저장.
- `ProductFacade.create`: 기존 브랜드 확인·상품 생성 뒤 `LikeService.initializeCount`를 호출해 같은 트랜잭션에 0 카운터 생성.
- `LikeRepositoryImpl`: 등록 INSERT 실제 성공 시 +1, 조건 DELETE 실제 반영 건수만큼 -1. 특정 유니크 충돌의 중복 등록은 0회 증감. 관계와 카운터는 같은 트랜잭션에서 commit/rollback. 카운터 누락·감소 시 음수 가능 상태는 IllegalStateException으로 실패시키며 관계 변경도 rollback.
- 조회 `countByProductIds`: GROUP BY 대신 카운터 일괄 조회. 요청 ID의 카운터가 누락되면 0으로 감추지 않고 실패. 올바르지만 오래된 양수 값까지 매 조회 검출하는 구조는 아니며 정합성은 원자적 쓰기와 대조/복구 절차로 유지한다.
- 기존 LikeRepository의 save/delete 보조 경로도 관계+카운터 경로로 통일. LikeJpaRepository의 집계 메서드는 테스트에서 원본 관계 확인용으로 남아 있고 고객 조회에서는 사용하지 않는다.
- 삭제 상품의 관계·카운터는 남기고 본인 취소를 허용한다. 최초 201·중복 200·취소 204, 삭제 비노출 계약은 유지.

### 테스트와 초기화

- 새 `LikeCounterIntegrationTest` 7건: 상품/카운터 함께 생성·rollback, HTTP 상태·상세/목록/내 좋아요 조회, 카운터 누락 시 등록 rollback·조회 실패, 잘못된 0 카운터 취소 rollback, 외부 트랜잭션 rollback, 삭제 후 취소, offline backfill 재실행/0개/삭제 상품 검증.
- 구현 전 새 테스트 6건이 카운터 테이블 미존재 등으로 실패하는 RED를 확인했다. 구현 후 좋아요 관련·ProductFacade·ArchUnit 총 23건 통과. 당시 Checkstyle의 unused import 1건을 제거했다.
- 기존 LikeConcurrencyTest는 실제 ProductFacade로 상품을 만들고 각 시나리오 후 카운터와 관계 집계 일치를 추가 확인한다. ProductFacadeTest·ProductBrandFilterIntegrationTest·BrandRemovalTransactionTest의 fixture를 새 불변식에 맞췄으며 기존 기대값은 유지했다.
- 최종 `:apps:commerce-api:check :apps:commerce-api:bootJar` BUILD SUCCESSFUL. XML 결과: 50 suites, 267 tests, 실패 0·오류 0·skip 0. Checkstyle main/test와 ArchUnit 포함. 실행 중이던 작업은 모두 종료됐다.
- `scripts/sql/week4-20-like-counter-backfill.sql`: 쓰기 앱을 중지한 offline 전환용. CREATE IF NOT EXISTS → 원본 READ/카운터 WRITE table lock → 0개·삭제 상품 포함 집계 upsert → unlock → 누락/불일치·고아 검증. 이전 버전 쓰기 앱과 새 버전을 함께 운영하는 전환은 지원하지 않는다.
- `scripts/migrate-week4-like-counter.py`: 명시한 로컬 benchmark DB만 초기화하고 원본 관계·상품 건수/합계·인덱스 보존 확인. 기존 결과 덮어쓰기를 거부하고 앱 연결이 남으면 중단한다.
- 세 로컬 DB에 실제 적용 완료: loopers_benchmark 카운터 10,000행/좋아요 합 62,756, loopers_benchmark_100k 100,000행/628,186, loopers_benchmark_100k_hotspot 100,000행/678,186. 모두 mismatch=0·orphan=0. 카운터 테이블은 **유지해야 하는 실제 스키마**다. [원본 · counter-backfill.json](measurements/like-counter-results.json).
- SQL 01~19는 직전 작업에서 정리 완료한 상태 유지. 20도 짧은 목적 주석과 이 절 링크만 둔다.

### 작업 재개 기록 (Claude Code)

사용량 소진으로 멈춘 지점을 다시 확인했다. 작업 트리의 카운터 코드는 깨지지 않았고, 실패 원인은 로컬 Docker/testcontainers 소켓 문제(`fix-docker-testcontainers` 스킬로 알려진 증상)였다. colima 터널을 다시 연결한 뒤 `:apps:commerce-api:test`(`LikeCounterIntegrationTest` 등 관련 스위트)와 전체 `:apps:commerce-api:check`를 재실행해 **267개 테스트, 실패·오류·skip 0, Checkstyle·ArchUnit 통과**를 확인했다. 커밋·push는 하지 않았다.

**1번(실제 API 실행) 완료.** `apps/commerce-api/build/libs/commerce-api-a083b48.jar`를 새로 빌드하고 `scripts/measure-week4-100k-api.py --jar apps/commerce-api/build/libs/commerce-api-a083b48.jar --output docs/week4/measurements/100k-counter-api.json`을 실행했다. `docker compose`가 testcontainers용 colima 컨텍스트가 아니라 원래 `mysql-benchmark` 컨테이너가 있는 `desktop-linux` 컨텍스트를 봐야 해서 `DOCKER_CONTEXT=desktop-linux`를 지정했다(`DOCKER_HOST`를 전역으로 바꾸면 기존 benchmark 컨테이너를 못 찾는다).

| DB                             | HTTP 중앙값 |     p95 | SELECT 수 | 97407 likeCount |
| ------------------------------ | ----------: | ------: | --------: | --------------: |
| loopers_benchmark_100k(일반)   |     17.84ms | 32.90ms |         5 |               2 |
| loopers_benchmark_100k_hotspot |     13.09ms | 15.32ms |         5 |          50,002 |

SQL 캡처로 좋아요 집계 대신 `select product_id, like_count from product_like_count where product_id in (...)`가 실행됨을 확인했다(업무 SELECT 5개 유지). 응답의 `likeCount`도 두 DB에서 기대값과 정확히 일치한다.

**2번(13절 대비 비교).** 카운터 적용 전후 실제 캡처에서 좋아요 관계 집계가 카운터 20행 조회로 바뀌었고 두 DB의 응답이 독립 기대값과 일치했다. 별도 JVM·각 30표본의 HTTP 지연은 분포 동등성이나 순수 개선 배율의 증거로 사용하지 않는다. 13절도 10만 건 데이터이며, 이전 기록의 “13절의 1만 건” 표기는 정정한다. JDBC 후보의 동일 조건 읽기·혼합 부하 비교는 15절 원본을 근거로 별도 설명한다.

**3~5번(ADR·design/plan/setup 정식 갱신, 최종 코드·문서·DB 대조 보고) 완료.** [ADR 0020](../adr/0020-like-count-denormalized-counter.md) 작성, [ADR 0002](../adr/0002-like-count-is-derived.md)를 Superseded로 변경. design.md·plan.md·setup.md의 중간 저장 안내와 "미결정" 서술을 실제 적용 상태로 갱신했다. ADR 0020에는 3주차 동시성 테스트 재검증 결과와, `BrandRemovalTransactionTest`·`LikeConcurrencyTest`의 fixture 변경 이유(카운터 불변식에 맞춘 것, `LikeConcurrencyTest`는 카운터-관계 일치 검증을 추가로 얻음)도 기록했다.

## 17. 브랜드 중복 조회 제거

`ProductQueryService.getList()`가 `brandService.get(brandId)`로 브랜드를 확인한 뒤, `enrich()` 안에서 `brandService.getAllActiveByIds(...)`로 같은 브랜드를 다시 조회하고 있었다. 9절에서 이미 업무 SELECT 5개를 나열했지만("브랜드 확인·COUNT·상품 페이지·브랜드 정보·좋아요 집계") 그중 "브랜드 확인"과 "브랜드 정보"가 중복이라는 점은 그때 짚지 않았다.

`enrich(Collection<ProductModel>, BrandModel)` 오버로드를 추가해 이미 조회한 브랜드를 재사용하도록 고쳤다. `get(productId)`·`getActiveByIdsInOrder(...)`는 변경하지 않았다 — 두 메서드는 brandId가 미리 정해지지 않아 기존 일반 조회(`enrich(products)`, 내부에서 `getAllActiveByIds`)가 그대로 필요하다.

**회귀 확인:** `:apps:commerce-api:test --tests '*ProductQueryServiceTest*' --tests '*ProductBrandFilterIntegrationTest*' --tests '*ProductFacadeTest*' --tests '*ProductV1ControllerTest*'`와 전체 `:apps:commerce-api:check` 모두 통과(267건, 실패·오류·skip 0, Checkstyle·ArchUnit 포함). `ProductBrandFilterIntegrationTest.preservesBrandAndPageValidation`(존재하지 않는 브랜드 ID로 조회하면 에러)이 그대로 통과해, 브랜드 존재 검증 동작은 바뀌지 않았음을 확인했다.

**실제 API 재측정:** `scripts/measure-week4-100k-api.py`(같은 100k·hotspot DB, 새 jar)로 다시 측정했다. 업무 SELECT가 4개로 줄었음을 확인하고 스크립트의 `assert ... == 5`도 `== 4`로 함께 갱신했다. 응답의 브랜드 정보(`"brand":{"id":7,"name":"W4 Brand 007"}`)와 `likeCount`(일반 DB 2, hotspot DB 50,002)는 이전 측정과 동일하게 정확했다.

| DB                             | HTTP 중앙값 |     p95 | SELECT 수 |
| ------------------------------ | ----------: | ------: | --------: |
| loopers_benchmark_100k(일반)   |     11.76ms | 12.73ms |         4 |
| loopers_benchmark_100k_hotspot |     11.61ms | 12.72ms |         4 |

[측정 · 100k-brand-dedup-api.json](measurements/index-results.json). 직전 측정([100k-counter-api.json](measurements/like-counter-results.json), 중앙값 17.84ms·13.09ms, SELECT 5개)보다 낮게 나왔지만, 별도 JVM·소표본 간 비교이고 브랜드 PK 단건 조회 자체가 이미 저렴해서 이 차이 전부를 이 변경의 효과로 단정하지 않는다. SQL 개수를 줄였다는 사실과 체감 가능한 지연 개선을 구분한다.

<a id="verified-review"></a>

## 18. 검증 근거·ADR 재검토와 캐시 범위 정리

사용자 요청으로 assignment.md, 실제 코드·테스트 소스·현재 XML·측정 JSON·ADR을 대조했다. 이번 변경은 문서 정리이며 Java 코드·DB·측정 원본은 바꾸지 않았다. 새 성능 측정이나 테스트 실행을 했다고 기록하지 않는다.

### 범위 확인

assignment Implementation Quest 1~2는 측정 대상을 브랜드 필터+price_asc 첫 페이지 하나로 지정하고, Quest 3은 공통 조회 한 경로의 Redis cache-aside를 요구한다. 다른 정렬의 기존 결과·오류 계약은 유지해야 하지만 최신순·좋아요순까지 모두 최적화하거나 새 인덱스를 추가하는 것이 캐시의 선행 조건은 아니다. 앞선 리뷰에서 다른 정렬 최적화를 우선 추천한 것은 추가 범위였으며 현재 작업의 필수 후속으로 두지 않는다.

현재 범위는 브랜드 7·가격순·page=0·size=20이다. 상품 DB 정렬·페이지, 카운터 조회, 브랜드 재사용을 유지하고 다음에는 공통 조회 한 경로의 캐시 범위·정합성 정책을 설계하는 것을 권고한다. JOIN·다른 정렬·새 카운터·추가 캐시 계층을 필수 단계로 늘리지 않는다.

상품 정보와 좋아요의 분리, 주기 갱신 인기순, 무효화 횟수 감소는 추가 설계 후보로 분류했다. 이 시점의 likes_desc는 현재 누적 좋아요 수로 정렬하며 순위 지연을 허용하는 정책을 채택하지 않았다. 주간 인기·최근 시간대 순위는 별도 정의가 필요하다.

### 확인한 사실과 정정

- 최신 측정 원본 100k-brand-dedup-api.json의 두 DB 모두 업무 SELECT 4개이며 카운터 쿼리의 실제 읽기 20행이다. 원본은 source_unchanged=true를 기록한다.
- 현재 테스트 XML은 50 suite·267건, 실패·오류·skip 0이다. LikeCounterIntegrationTest 7건·LikeConcurrencyTest 7건·ArchUnit 성공 항목을 직접 확인했다. 이 결과는 기존 실행의 증거이며 이번 재실행 결과가 아니다.
- counter-backfill.json의 3개 DB 모두 missing_or_mismatched=0·orphan_counters=0 기록을 확인했다. 현재 DB를 다시 측정한 값으로 표현하지 않는다.
- ADR/설계/계획/로그에서 “응답 시간이 더 이상 구분되지 않는다”, “집계 방식 HTTP 수십 배 차이가 사라졌다”, “읽기 전체가 상수 비용”, “누적 좋아요가 많으므로 캐시 무효화가 잦다”를 제거했다. 같은 페이지 SQL 비교와 별도 JVM HTTP 비교를 구분하고, 13절 데이터를 1만 건으로 적은 부분을 10만 건으로 정정했다.
- setup의 서로 다른 시점에 붙인 “현재/최신 DB 상태” 안내를 하나의 최근 검증 구성 안내로 정리했다. 과거 실험·실측은 기록으로 보존한다.

### 도메인·트랜잭션 판단 검토

ADR 0002는 파생 카운터의 정합성 책임을 피하고 관계 COUNT를 사용하는 결정이었다. ADR 0017은 유니크 제약·조건 DELETE로 중복·취소 계약을 지키며 별도 카운터를 쓰지 않는 결정이었다. 이번에는 별도 카운터 저장을 도입했으므로 “문제가 다르기 때문에 기존 결정이 전혀 바뀌지 않았다”는 설명은 충분하지 않다.

ADR 0020을 다시 작성해 0002를 대체하고 0017의 카운터 미사용 부분을 변경한다고 명시했다. 0017에도 후속 링크와 변경된 범위를 반영했다. 외부 최초/중복/취소·삭제 경합 계약은 유지한다. LikeCountModel은 별도 파생 저장 상태이며 상품 재고 aggregate에 좋아요 증감 책임을 추가하지 않았다. 상품/카운터 생성 조정은 ProductFacade, 관계 의미·쓰기 요청은 LikeService, 두 SQL의 원자적 저장은 LikeRepositoryImpl, 응답 조합은 ProductQueryService에 있다. 기존 패키지 의존 방향은 ArchUnit으로 검증된 범위에서 유지한다.

검증표에는 실제 코드 테스트와 실험용 JDBC failpoint를 구분해 적었다. 실제 구현에서 누락/감소 실패 rollback, 외부 TX rollback, 중복·동시 등록/취소, 삭제 후 취소, 상품/카운터 동시 생성·rollback, offline backfill 재실행을 확인했다. 직접 SQL 우회에 의한 양수 불일치 검출, 모든 동시성 interleaving, 카운터 행 timeout의 별도 HTTP 시나리오, 무중단 migration, Redis 장애는 이번 증거로 확인된 범위가 아니다. 테스트가 통과했다는 사실을 이 범위 전체의 보장으로 확대하지 않는다.

### 캐시 다음 단계 — 제안, 아직 미구현

가격순에서 좋아요 값은 순서·페이지 선택에 사용되지 않는다(ProductQueryService의 PRICE_ASC 비교/DB ORDER BY 확인). 따라서 캐시 payload에서 좋아요 값을 분리하고 카운터에서 현재 값을 합성하면, 좋아요 등록/취소가 그 캐시의 내용을 바꾸지 않도록 범위를 설계할 수 있다. 이는 코드 의존성에 따른 설계 후보이며 hit rate·무효화 감소량·지연 개선은 아직 측정하지 않았다. hit에 남는 DB 비용을 포함해 DB-only/miss/hit을 비교해야 한다.

상품 생성·가격/이름 수정·상품/브랜드 삭제는 저장한 필드·순서·페이지에 영향을 줄 수 있어 commit 경계·실패·늦은 채움을 검증해야 한다. 현재 페이지에 없는 상품도 가격이 바뀌어 들어오거나 OFFSET 경계를 바꿀 수 있으므로 “캐시에 없으니 영향 없음”으로 무효화를 생략하지 않는다. 삭제 비노출·소유권·주문 판단은 완화하지 않는다. TTL 하나로 무효화 실패·늦은 재삽입을 모두 해결했다고 하지 않는다.

인기순을 별도 캐시 대상으로 고른다면 DB-only/miss/fallback 비용을 먼저 측정하고, 그 SQL의 계획·읽기량으로 인덱스 후보를 판단한다. 캐시 도입 전에 인기순 인덱스를 반드시 추가해야 한다는 규칙은 없다. 현재 likes_desc의 순서/값 최신성을 바꾸거나 별도 주기 갱신 인기순을 만들 때에는 먼저 정책을 합의해야 한다. 이 리뷰에서 5분 snapshot을 구현하거나 기존 계약을 변경하지 않았다.

이번 문서 리뷰의 권고는 **현재 가격순 범위를 유지한 캐시 설계로 진행**하는 것이다. 캐시가 실제 서비스에 필요한지·어떤 범위가 효율적인지는 아직 미검증이며 과제의 학습 구현 목적과 구분한다. 측정할 항목은 DB-only/miss/hit 결과 일치, 생략된 원본 SQL, hit rate·DB 호출량, 무효화 횟수, 지연 분포, Redis 실패/복구·늦은 채움 결과다.

## 19. plan 체크리스트를 완료 근거에 맞춰 갱신

사용자가 4단계까지 진행했는데 체크가 남아 있다고 지적해 plan의 1~4단계 항목을 실제 코드·작은 픽스처·측정 JSON·기존 check 결과와 대조했다. 미체크 표시는 작업 누락이 아니라 초기 계획의 표시가 갱신되지 않은 것이었다. 현재 브랜드 7 가격순 범위의 완료 항목을 체크하고 각 항목에 근거 링크를 붙였다. 좋아요 집계→카운터 전환, JOIN 실험/미적용, 선택 후속(기존 인덱스 제거·고부하 쓰기 한계)을 구분했다. 5~7단계 캐시 정책·구현·변경/장애/복구는 아직 미완료이며, 캐시 후 최종 회귀·기술 글·제출도 남아 있다. 이번에는 문서만 수정했으며 새 테스트를 실행한 것으로 기록하지 않는다.

<a id="index-reading-review"></a>

## 20. 인덱스 실험을 읽기·정렬·반환 순서로 설명

사용자가 설명이 어렵다고 지적해 design 7·8절, 이 로그 7·8.1절, SQL 09·10의 짧은 확인 목적과 Handler 결과서 요약을 정리했다. 숫자는 기존 price-first-index.json과 index-conditions.json의 EXPLAIN ANALYZE·반환 결과를 직접 대조했다. 가격 우선 scan 실제 1,600행·Filter/Limit 20행, 브랜드 우선 lookup 실제 20행·Limit 20행을 확인했다. 두 브랜드 자동/05 강제 경로는 range scan 실제 198행·Sort·Limit 20행을 확인했다.

이제 “실제 읽은 행 → 추가 정렬 → 반환한 행”으로 설명한다. Sort가 없더라도 읽기량이 다르며, 이번 IN 비교의 Sort를 모든 인덱스에 일반화하지 않는다. 가격 우선+IN은 이 원본에 없는 조합이라 실측 결과로 적지 않았다. 새로운 예시 가격·추정 행 수·성능 배율을 추가하지 않았고 측정 원본·SQL 실행 문장은 바꾸지 않았다. 새 DB 실험이나 테스트 실행이 아닌 기존 원본 대조와 설명 정리다.

<a id="price-cache-without-count"></a>
<a id="price-cache-basic"></a>

## 21. 가격순 상품 페이지와 기본 캐시 검증

대상은 브랜드 필터·가격순·page=0·size=20이다. 응답은 items·page·size이며 상품 페이지를 고른 뒤 해당 ID의 좋아요 수와 선택 사용자 헤더의 isLiked를 붙인다. 총상품수는 세거나 반환하지 않는다. 캐시에는 상품 ID·이름·가격·생성 시각만 저장한다. [ADR 0021](../adr/0021-price-page-cache-aside-with-after-commit-eviction.md), [목록 계약 ADR 0022](../adr/0022-product-list-without-total-count.md), [쉬운 흐름](cache-basics.md).

가격순 개인화 단계의 `:apps:commerce-api:check :apps:commerce-api:bootJar`는 55 suites·310건, 실패/오류/skip 0으로 통과했다. Checkstyle Main/Test와 ArchUnit을 포함한다. [해당 단계 검사 집계](measurements/price-cache-personal-validation.json). JAR SHA-256은 `93281c5be8991704062b40a7ec5fd55b79ef0bf270c22df17adb97aca49193d2`다.

| 검증 | 실제 확인한 범위 |
| --- | --- |
| 응답·페이지 | 세 정렬의 totalCount 부재, page·size, 동점·삭제 제외·다음/빈 페이지·입력 오류 |
| Redis 캐시 통합 20건 | DB/miss/hit 일치, TTL, 캐시 값의 totalCount/좋아요/isLiked/재고/브랜드 부재 |
| 변경 | 실제 관리자 PUT 뒤 고객 GET의 가격·이름·순서, 페이지 밖 상품·생성·삭제·브랜드 삭제 |
| live 합성 | 좋아요·재고·브랜드 표시 변경 시 가격순 페이지 재조회 없이 현재 좋아요 수·isLiked·브랜드 합성 |
| 트랜잭션 | commit 후 DEL, rollback 유지, 쓰기 트랜잭션 안의 미확정 캐시 저장 방지 |
| 경쟁/실패 | 래치로 통제한 늦은 채움, 강제 DEL 실패, 삭제 비노출, 만료 뒤 회복 |
| 실제 장애 | 전용 Redis Testcontainer stop/start, DB 우회·가격 변경 commit 유지·명시적 자신의 키 DEL 뒤 재채움 |
| 오류/범위 | 손상 JSON fallback, DB 페이지/활성 ID 오류 전파, 미지원 페이지·크기·정렬·무필터 우회 |
| 로컬 | 통합 6건·unit 2건의 변경·삭제·좋아요 합성·rollback·저장 후 만료 |

주문 금액·재고·포인트의 기존 회귀도 전체 검사에 포함된다. Redis 장애 테스트는 사용자의 공유 Redis가 아닌 별도 컨테이너를 중단한다. TTL 회복 테스트는 자신의 키를 100ms로 단축하며 기본 300초를 실제 기다린 것은 아니다. 재연결만으로 즉시 최신이라는 보장은 하지 않는다. 테스트 수와 API 측정 요청 수는 합산하지 않는다.

<a id="price-cache-providers"></a>

## 22. 캐시 없음·로컬·Redis 실제 API 비교

같은 인덱스·일반 10만 건 복제 DB·브랜드 7 가격순 첫 20개·상품별 좋아요 수·isLiked·브랜드·page/size와 사용자 헤더 95를 고정했다. 독립 기대 응답에 총상품수는 없다. Python이 현재 JAR의 HTTP API를 호출한다. provider 실행 순서를 DB→local→Redis / local→Redis→DB / Redis→DB→local로 바꿨다. 각 모드는 워밍업 100회 뒤 200회 측정했다.

| 경우 | 1차 중앙값(ms) | 2차 중앙값(ms) | 3차 중앙값(ms) | SELECT 수 |
| --- | ---: | ---: | ---: | ---: |
| 캐시 없음 | 10.051 | 10.273 | 10.028 | 5 |
| 로컬 miss | 8.491 | 8.607 | 8.467 | 5 |
| 로컬 hit | 10.695 | 10.021 | 10.063 | 5 |
| Redis miss | 10.334 | 9.762 | 10.872 | 5 |
| Redis hit | 12.494 | 10.823 | 11.123 | 5 |

대표 GET의 SQL은 모든 경우 5개이며 총상품수 COUNT는 없다. DB-only/miss는 브랜드·가격순 페이지·좋아요 카운터, hit는 브랜드·활성 ID·좋아요 카운터다. 양쪽 모두 활성 사용자 확인과 페이지 상품의 개인 관계 조회가 추가된다. 헤더 없는 별도 대표 GET은 SELECT 3개이며 isLiked=false였다. 시간 표본마다 hit 로그를 남긴 방식은 아니고, 통제한 조건과 측정 후 대표 SQL로 경로를 확인했다. p95·모든 시간 표본·실행 계획은 [원본](measurements/100k-price-cache-providers-personal.json)에 보존한다. [방법 설명](price-cache-comparison.md).

Redis JSON에는 items만 있고 가격순 첫 20개 ID가 기대값과 같았다. 현재 키의 크기는 원본의 redis_value_bytes/redis_key_memory_bytes에 기록한다. JVM RSS는 전체 앱이고 키 메모리와 동등한 캐시 메모리 지표가 아니다. 로컬 miss가 hit보다 짧은 결과는 순서·JIT·준비용 PUT 영향과 분리하지 못했다. 별도 JVM의 작은 차이를 보편적인 우열로 일반화하지 않는다.

<a id="price-cache-change-check"></a>

### 가격 변경과 두 앱

miss 준비는 같은 이름/가격의 관리자 PUT으로 정상 commit 후 DEL을 일으켰다. 이 PUT은 GET 시간 밖이다. 별도 변경 혼합에서는 실제 첫 상품 가격을 원래 값과 1 낮은 값으로 번갈아 바꾸고 변경 1회당 GET 10회를 보냈다. 매 GET의 전체 응답을 기대값과 비교했다. GET/PUT 시간은 각 API 전체 비용이며 정합성은 응답 비교로 확인한다. PUT은 캐시 삭제만의 순수 비용이 아니다.

두 JVM A/B가 같은 DB를 사용하도록 실행해 둘을 채운 뒤 A에서 가격을 변경했다. 세 번째 반복에서 local/Redis 모두 A는 최신, B는 local에 옛 가격 잔존/Redis에 최신 가격 반영을 확인했다. 한 컴퓨터에서 서로 다른 포트로 직접 요청한 실험이며 자동 요청 분배나 모든 경쟁을 검증한 것은 아니다. 정상 변경 후 순차 조회의 범위로 읽는다.

원본의 completed/source_unchanged/owned_resources_cleaned는 true다. 고유 복제 DB·권한·키·앱을 정리했고 원본 데이터·인덱스를 보존했다. 실험 스크립트는 [compare-week4-price-cache-providers.py](../../scripts/compare-week4-price-cache-providers.py)다.

<a id="price-cache-skew"></a>

## 23. 상품 쏠림·동시 조회

원본을 복사한 일반/상품 쏠림 두 DB를 사용했다. 상품 100,000건·인덱스·가격·삭제 상태·카운터를 유지하고 쏠림 복제 DB만 ID<=90,000의 브랜드를 7로 바꿨다. 브랜드 7의 활성 상품은 일반 990개/쏠림 87,390개다. 건수는 데이터 준비용 확인이고 API 총상품수 조회가 아니다. 실제 API는 가격순 첫 20개와 좋아요 수·사용자 95의 isLiked를 반환한다.

순차는 모드별 워밍업 100회/측정 200회, 세 번 반복하며 앱과 hit/miss 순서를 바꿨다. 아래 범위는 세 중앙값 중 최솟값~최댓값이다.

| 경우 | 일반 중앙값(ms) | 쏠림 중앙값(ms) |
| --- | ---: | ---: |
| 캐시 없음 | 9.472~9.702 | 9.830~12.408 |
| 로컬 miss | 8.859~9.703 | 8.386~9.710 |
| 로컬 hit | 8.572~10.371 | 8.770~10.561 |
| Redis miss | 9.762~10.739 | 10.581~11.935 |
| Redis hit | 9.461~11.235 | 9.803~12.560 |

모든 대표 순차 SQL은 5개이며 총상품수 COUNT는 없었다. 첫 반복의 DB-only 대표 EXPLAIN ANALYZE에서 일반·쏠림 모두 idx_product_brand_price의 실제 rows=20, LIMIT 20·Sort 없음이었다. 실제 SQL·계획은 원본에 보존했다. 데이터 준비의 COUNT나 checksum을 API 시간에 넣지 않았다.

동시는 한 앱에 Python 20개 스레드를 barrier에서 함께 출발시켰다. 준비용 3묶음 뒤 10묶음×20요청, 세 번 반복했다. benchmark DB 풀 최대는 40이다. 시작 시 빈 상태는 모든 요청의 miss를 뜻하지 않는다. SQL 시작/서버 도착을 동시에 강제하거나 지속 최대 처리량을 측정한 것은 아니다.

| 경우 | 일반 p95(ms) | 쏠림 p95(ms) |
| --- | ---: | ---: |
| 캐시 없음 | 35.042~48.154 | 35.426~42.583 |
| 로컬 / 시작 시 빈 캐시 | 34.794~53.387 | 34.661~58.934 |
| 로컬 / 미리 채운 캐시 | 36.582~38.460 | 39.404~69.939 |
| Redis / 시작 시 빈 캐시 | 40.767~61.760 | 34.777~49.017 |
| Redis / 미리 채운 캐시 | 31.125~44.668 | 36.083~45.696 |

### 별도 동시 20요청에서 상품 페이지를 다시 읽은 횟수

| 경우 | 일반 1/2/3차 | 쏠림 1/2/3차 |
| --- | ---: | ---: |
| 캐시 없음 | 20 / 20 / 20 | 20 / 20 / 20 |
| 로컬 / 빈 캐시 | 7 / 8 / 3 | 4 / 7 / 4 |
| 로컬 / 채운 캐시 | 0 / 0 / 0 | 0 / 0 / 0 |
| Redis / 빈 캐시 | 10 / 11 / 11 | 6 / 8 / 10 |
| Redis / 채운 캐시 | 0 / 0 / 0 | 0 / 0 / 0 |

모든 별도 동시 묶음에서 총상품수 COUNT=0, SELECT=100이었다. 사용자 확인·개인 관계 SELECT는 각각 20번이며 개인 관계 SQL 한 번에 해당 페이지 상품 20개만 전달했다. 채운 캐시에도 브랜드·활성 ID·좋아요 카운터·사용자·개인 관계의 DB 조회가 남는다. 빈 캐시는 같은 페이지를 여러 번 DB에서 만드는 현상이 남았으며 동시 miss를 합치는 기능은 없다. SQL 로깅은 별도 요청 묶음으로 수행하므로 그 횟수를 시간 표본의 정확한 hit rate로 해석하지 않는다.

측정 GET 12,000개(순차 6,000/동시 6,000)의 전체 응답이 독립 seed 기대값과 같았고 동시 오류는 0이었다. 18개 앱 실행, 원본 checksum·건수/인덱스 및 카운터 checksum·건수/합계 보존, 자신의 복제 DB·권한·키·프로세스 정리를 확인했다. [원본](measurements/100k-price-cache-skew-personal.json), [실행 코드](../../scripts/measure-week4-price-cache-skew.py), [쉬운 설명](price-cache-skew.md).

<a id="price-cache-close-checklist"></a>

## 24. 가격순 범위의 판단과 Checklist

가격순 첫 페이지는 인덱스를 사용하는 상품 페이지와 좋아요 카운터 조회로 구성된다. hit도 필요한 DB 확인이 남으며 Redis가 반드시 필요하다는 성능 근거는 이번 로컬 실험에서 얻지 못했다. 과제의 기본 Redis 구현은 학습 대상으로 유지하고 운영 도입은 실제 요청량·요구 응답 시간·변경 반영 책임으로 판단한다. 동시 miss 중복 방지·다른 정렬 캐시·장기 부하를 이번 기본 과제의 선행 조건으로 추가하지 않는다.

[design의 가격순 캐시 선택](design.md#price-cache-choice)에 현재 로그인 조회의 세 반복 중앙값 범위, 캐시 없음·로컬·Redis의 선택 이유, 측정한 비용과 미측정 비용을 모았다. 수치는 22절의 기존 원본과 대조했고 이번 설명 보완에서 새 성능 측정이나 코드·설정 변경은 하지 않았다. 로컬의 다중 앱 무효화와 추가 정합성 구조는 후속 판단으로 남긴다.

과제 Checklist의 완료 근거를 아래에 연결했다. 현재 목록 계약은 [ADR 0022](../adr/0022-product-list-without-total-count.md)와 [ADR 0023](../adr/0023-product-list-personal-like-state.md)을 따른다.

| 완료 | 항목 | 근거 |
| --- | --- | --- |
| [x] | 실제 API의 필터·정렬·페이지·삭제 조건 | ProductBrandFilterIntegrationTest, 세 정렬의 Controller 응답 검사 |
| [x] | 같은 데이터의 SQL·계획·결과·지연 비교 | [인덱스 원본](measurements/index-results.json), 현재 [다섯 경우 원본](measurements/100k-price-cache-providers-personal.json)·[쏠림 원본](measurements/100k-price-cache-skew-personal.json) |
| [x] | 좋아요 수와 likes_desc 전체 정렬 | ProductQueryServiceTest, LikeCounterIntegrationTest, LikeConcurrencyTest |
| [x] | miss/hit 일치·원본 호출 재사용 | Redis/로컬 통합, 실제 API의 페이지 선택 SQL 생략·활성 ID 확인 |
| [x] | 키·TTL·개인 정보·영향 쓰기 | ADR 0021, PricePage·ProductFacade·BrandFacade·AFTER_COMMIT listener |
| [x] | 변경·삭제·rollback·실패/경쟁의 보호 | PricePageCacheIntegrationTest의 실제 PUT·삭제·rollback·DEL 실패·늦은 채움·만료 |
| [x] | Redis 실제 장애·우회·복구·정리 | 전용 Testcontainer stop/start·DB fallback·명시적 자신의 키 DEL 뒤 재채움 |
| [x] | 회귀·lint·ArchUnit·비용/한계 | [가격순 단계 310건 집계](measurements/price-cache-personal-validation.json), 측정 문서와 ADR |

추가한 카운터는 원본 관계와 중복·동시 변경 후 일치하는지 확인했다([ADR 0020 검증표](../adr/0020-like-count-denormalized-counter.md#validation-and-limits)). 인기순 캐시·이벤트/배치 집계·Sorted Set을 구현해야 이 항목이 완료되는 것은 아니다.

기술 글 완성과 PR 정리/제출은 남아 있다. 자동 검증 완료는 사용자의 직접 실습 완료를 뜻하지 않는다.

## 25. 가격 변경 흐름 읽기

[캐시 기본 문서의 A/B/C 예시](cache-basics.md#가격-변경-흐름을-예로-읽기)는 저장 → 재사용 → 관리자 변경 commit 뒤 삭제 → 다음 조회 재채움 순서를 설명한다. 예시 가격은 측정값이 아니다. 별도 수동 실습 환경을 추가하지 않는다. 실제 변경·삭제·rollback·실패/늦은 채움은 21절의 현재 통합 테스트와 22절의 실제 API로 확인한다.

<a id="redis-read-cost"></a>

## 26. Redis GET·JSON 변환 보조 측정

기존 PricePageRedisConfig·StringRedisTemplate·PricePage와 Boot JacksonAutoConfiguration/프로젝트 JacksonConfig를 재사용하는 별도 Java 프로그램을 실행했다. DB에서 사전에 읽은 브랜드 7의 가격순 20개를 실험 전용 키에 저장했고 JSON은 1771바이트였다. HTTP 앱의 요청 중간 계측이나 캐시 어댑터 전체 시간은 아니다.

독립 JVM 세 개, 각각 워밍업 1,000회 뒤 순차 1,000회다. 매 요청에 System.nanoTime으로 GET 직전/문자열 수신 직후/JSON 변환 직후를 기록했다. 값 비교·로그 출력은 시간 밖이며 측정 루프에 HTTP/DB 조회가 없다. 같은 연결을 재사용하고 기존 200ms 연결/명령 제한을 사용했다.

| 구간 | 중앙값(ms) | p95(ms) |
| --- | ---: | ---: |
| Redis GET → 문자열 수신 | 0.541~0.720 | 0.770~2.669 |
| JSON → 상품 목록 | 0.046~0.061 | 0.078~0.173 |
| GET 시작 → 변환 완료 | 0.591~0.794 | 0.834~2.780 |

범위는 세 반복 통계의 최솟값~최댓값이다. 전체 구간은 각 요청에서 직접 계산한 합계이며 구간별 중앙값/p95를 단순 합산하지 않았다. 측정 3,000번 모두 원본 문자열/DTO와 같았고 기존 캐시 어댑터 결과도 같았다. JAR/현재 컴파일·소스 정합성, 원본 데이터·인덱스 보존·실험 키 삭제를 확인했다. 원시 표본·payload·hash·Jackson 모듈·TTL은 [원본](measurements/price-cache-redis-read-cost-personal-build.json)에 보존한다.

GET에는 클라이언트 대기·네트워크 왕복·Redis 처리·문자열 변환이 섞인다. 순수 네트워크 시간·운영 원격 통신 비용으로 부르지 않고 API 전체 시간에서 빼지 않는다. 기술 글에는 짧은 보조 근거로 사용하며 추가 세분화를 과제 완료의 조건으로 요구하지 않는다.

```bash
python3 scripts/measure-week4-redis-read-cost.py \
  --jar apps/commerce-api/build/libs/commerce-api-a083b48.jar \
  --output docs/week4/measurements/price-cache-redis-read-cost-personal-build.json
```

새 출력 경로로 실행해 기존 측정 원본을 덮어쓰지 않는다. [Python 실행기](../../scripts/measure-week4-redis-read-cost.py)는 현재 JAR의 클래스를 검사·임시 추출하고 [Java 측정 프로그램](../../scripts/probes/PriceCacheReadCost.java)을 컴파일해 실행한다. Python 구문·Java 컴파일·실제 세 JVM 실행과 결과 검증을 완료했다.

<a id="product-list-personal-like-state"></a>

## 27. 상품 목록의 내 좋아요 표시

목록 항목에 isLiked를 추가했다. ProductListQueryService가 공통 ProductQueryService의 결과를 받아 사용자 확인과 페이지별 개인 관계 조회를 조정한다. LikeRepository는 `user_id = ? AND product_id IN (...)`으로 해당 페이지의 좋아요한 상품 ID만 한 번에 반환한다. 빈 페이지는 관계 SQL을 실행하지 않는다. 원본 관계와 카운터 쓰기·정렬·캐시 키/값/TTL은 유지한다. [ADR 0023](../adr/0023-product-list-personal-like-state.md).

| 검증 | 확인한 결과 |
| --- | --- |
| 사용자 A/B·Redis·로컬 hit | 같은 상품 캐시에서도 각자 다른 isLiked, 공통 캐시 값 유지·재조회/무효화 없음 |
| 등록·중복 등록·취소 | 실제 API 완료 뒤 GET의 하트와 현재 카운터 일치 |
| rollback | 취소 중에는 false, rollback 후 새 조회는 원래 true, 공통 캐시 유지 |
| 페이지·정렬 | 선택된 페이지 ID만 전달, 순서 유지, 다음/빈 페이지·세 정렬 |
| 익명·입력 오류 | 헤더 없음 false, 없는/삭제된 사용자 404, 숫자 아닌 헤더 400 |
| 오류 처리 | 개인 관계 DB 오류를 false로 숨기지 않고 전파 |

전체 310건은 현재 개인화 구현을 포함한 회귀 검사다. 22~23절은 같은 JAR로 다시 실행했으며 사용자 95의 isLiked를 독립 seed 계산과 매 응답 비교했다. 일반 페이지는 12개 true/8개 false, 쏠림 페이지는 20개 false였다. 다른 개인 관계 분포까지 대표한다고 주장하지 않는다. 헤더 없는 조회는 별도 표로 구분했다. 이전 개인화 전 원본 JSON은 당시 측정으로 남기며 현재 개인화 경로의 수치로 사용하지 않는다. 좋아요 변경과 겹쳐 이미 시작한 트랜잭션까지 최신이라고 주장하지 않는다.

## 27-1. 쿼리 패턴에 따른 인덱스 선정 근거 정리

design 3절에 WHERE·ORDER BY·LIMIT과 인덱스 컬럼의 대응 표, 기존·가격까지만·전체 정렬·가격 우선 후보의 접근 행 수와 Sort 비교를 모았다. 기존 설명은 2~8절에 있었으며 카디널리티의 역할, 두 고정 조건의 순서까지 최적이라고 증명한 것은 아니라는 한계, 실제 호출 빈도 미측정을 명시했다.

9절에는 현재 인덱스별 용도와 남은 판단을 추가했다. 기존 브랜드 인덱스의 ID 정렬 근거, 개인 관계 유니크 인덱스와 카운터 PK의 현재 역할, 과거 집계용 product_id 인덱스의 재검토 필요성을 구분했다. 현재 목록에는 총상품수 COUNT가 없으므로 과거 COUNT 실험을 현재 유지 근거로 사용하지 않는다.

현재 모델·Repository·ADR 0020/0023 및 기존 측정 원본과 대조한 문서 보강이다. 코드·인덱스·정책·성능 원본을 변경하거나 새 측정을 실행하지 않았다. 문서 링크·표와 변경 범위를 확인했으며 테스트·Checkstyle·ArchUnit은 이번 문서 변경에서 재실행하지 않았다.

<a id="likes-cache-basic"></a>

## 28. 좋아요순 ID 페이지 캐시와 실제 API 비교

이 절은 ADR 0024 단계의 기록이다. 현재 정책·코드는 [30절](#popular-cache-snapshot)과 [ADR 0025](../adr/0025-popular-page-snapshot-with-ttl.md)를 따른다. 아래 시간·326건 검사는 해당 이전 빌드의 결과이며 현재 구현 재측정이 아니다.

현재 인기순 실험은 브랜드 7의 기존 likes_desc·page=0·size=20·X-USER-ID=95다. 상품 10만 건, 기존 상품/좋아요 인덱스와 별도 카운터를 그대로 복사했다. 새 seed 생성·원본 변경·인덱스 추가는 없다. 총상품수는 제공하지 않는다. [결정 ADR 0024](../adr/0024-likes-page-cache-with-commit-eviction.md), [설계·판단](design.md#likes-cache-choice).

### 기준선과 현재 빌드 구분

구현 전 JAR의 DB-only API를 세 JVM에서 워밍업 100/측정 200회 실행했다. 세 중앙값은 15.294 / 15.243 / 15.199ms였다. 이 원본을 아래 측정 JSON의 baseline_before_cache에 그대로 포함했다. 구현 전 hash는 `93281c5be8991704062b40a7ec5fd55b79ef0bf270c22df17adb97aca49193d2`, 현재 검증/비교 hash는 `2499b6930af6702a8a81f1b147fdd637214401a9ae97fc4139ddc4a220b7349b`다. 이전 값을 현재 빌드의 시간으로 사용하지 않는다.

현재 빌드에서도 DB-only/miss/hit을 새로 측정했다. provider 순서는 DB→Redis / Redis→DB / DB→Redis이고 Redis hit/miss 순서도 교대했다. 모드마다 워밍업 100/측정 200회다. HTTP 요청부터 본문 수신까지를 재고 JSON 기대값 비교는 시간 밖에서 한다. 각 GET에서 독립 seed 계산의 상품·순서·이름·가격·좋아요 수·isLiked·브랜드·page/size가 같은지 확인했다.

| 경우 | 1차 중앙값(ms) | 2차 중앙값(ms) | 3차 중앙값(ms) | 세 p95 범위(ms) |
| --- | ---: | ---: | ---: | ---: |
| 캐시 없음 | 15.446 | 14.309 | 14.920 | 18.058~19.618 |
| Redis miss | 15.845 | 16.955 | 16.049 | 18.896~21.081 |
| Redis hit | 10.786 | 9.242 | 10.932 | 10.382~14.981 |

miss는 전용 Redis 제어 연결로 자신의 키를 DEL한 뒤 GET했다. DEL은 GET 시간 밖이며 실제 좋아요 쓰기 비용과 분리한다. hit는 미리 채운 뒤 변경 없이 반복했다. 각 모드의 시간 측정 후 대표 요청 SQL을 수집해 source 경로/ID 경로를 확인했다. 매 시간 표본의 hit 로그를 남긴 것은 아니다.

### SQL·캐시 값

정상 비어 있지 않은 로그인 요청의 업무 SELECT는 브랜드·상품·카운터·사용자·개인 관계의 5개다. 대표 9개 요청에서 상품 총수 COUNT는 0번이었다. DB-only/miss의 상품/카운터 실제 rows는 각각 990, hit는 각각 20이었다. 개인 관계는 모두 페이지 ID 20개로 조회했다. 전체 순위 선택은 원본 경로의 Java comparator이고 hit는 ID 순서를 유지하며 페이지 정보만 다시 합성한다. DB 실행 계획의 Sort 유무만으로 전체 정렬 비용을 판단하지 않는다.

Redis 값은 ids 한 필드, 상품 ID 20개·128바이트였다. 이름·가격·좋아요 수·isLiked를 저장하지 않는다. SET+TTL 300초이며 hit로 만료를 연장하지 않는다. 표시 변경 시에는 ID 순위를 재사용하고 현재 상품 정보를 DB에서 읽는다.

### 실제 좋아요 변경의 응답·비용

seed에서 사용자 95가 좋아요하지 않은 페이지 밖 상품 307에 POST(201)/DELETE(204)를 번갈아 실행했다. 좋아요 하나로 이 상품이 페이지에 진입하며 취소 후 원래 목록이 된다. 각 패턴의 워밍업 100 GET/측정 200 GET 동안 전 응답을 base/changed_response와 비교했다. DB-only와 Redis 모두 동일하게 수행했다.

| 조건 | 캐시 없음 GET 중앙값(ms) | Redis GET 중앙값(ms) |
| --- | ---: | ---: |
| 변경 1회 / GET 10회 | 13.544~14.178 | 8.380~8.616 |
| 매 GET 전 변경 | 12.029~12.806 | 13.467~14.005 |

| 조건 | 캐시 없음 변경 API 중앙값(ms) | Redis 변경 API 중앙값(ms) |
| --- | ---: | ---: |
| 변경 1회 / GET 10회 | 8.437~8.639 | 8.004~8.928 |
| 매 GET 전 변경 | 6.418~6.790 | 7.476~7.859 |

변경 시간은 POST와 DELETE를 합친 표본의 API 전체 시간이다. 사용자/상품 확인·관계/카운터 변경·commit·해당 모드의 DEL·응답을 포함하며 순수 Redis DEL 비용으로 빼서 해석하지 않는다. 취소는 실제 변경된 경우 브랜드 ID 확인 조회도 수행한다. 각 반복의 변경 표본은 1:10 패턴 20개, 1:1 패턴 200개다. p95와 원시 표본은 원본에 둔다. 실험 패턴으로부터 예상하는 Redis 재사용 비율은 각각 0.9/0이며 운영 hit rate나 개별 GET의 실측 hit 로그가 아니다. 테스트에서 중복 등록·없는 취소는 무효화하지 않는지도 확인했다.

### 20명 동시 조회

Python 스레드 20개를 barrier에서 함께 출발시켰다. 준비용 3묶음 뒤 10묶음×20 GET을 측정하고 각 모드를 세 번 반복했다. 서버 도착/SQL 시작 시점까지 같게 강제하지 않았고 DB 풀 최대는 40이다. 빈 캐시는 묶음 직전 자신의 키를 지운 상태이며 전부 miss임을 뜻하지 않는다.

| 시작 상태 | 세 p95 범위(ms) |
| --- | ---: |
| 캐시 없음 | 71.041~89.800 |
| 시작 시 빈 Redis | 70.405~110.879 |
| 미리 채운 Redis | 25.326~26.203 |

현재 빌드의 측정 GET 6,000개는 순차 1,800·변경 혼합 2,400·동시 1,800개다. 전 응답이 기대값과 같았고 동시 오류는 0이었다. 실제 측정 변경 API는 1,320회다. 이전 기준선 GET 600회는 별도 기록이다. 변경 혼합이 끝난 실험 DB의 관계/카운터 복원, 원본 데이터·인덱스·카운터 checksum 보존과 자신이 만든 DB·권한·키·앱 정리를 확인했다.

### 회귀·실패·판단의 범위

전체 검사는 56 suites·326건, 실패/오류/skip 0이다. Checkstyle Main/Test·ArchUnit을 포함한다. 첫 검사에서는 미사용 import 1개로 Checkstyle이 실패했으며 이를 제거하고 실제 취소 여부 반환 검증을 보강한 뒤 전체 check를 완료했다. 새 LikesPageCacheIntegrationTest 15건은 정상/오류/변경/rollback/삭제/사용자 경계/늦은 채움/DEL 실패/실제 Redis 장애와 회복을 포함한다. 기존 가격순·카운터 동시성·주문 회귀도 유지했다.

실패/늦은 채움에서는 현재 좋아요 숫자와 하트가 반영돼도 오래된 ID 순서가 남을 수 있다. 테스트용 100ms 만료 뒤 재선택을 확인했으며 기본 TTL 300초나 모든 요청에 적용되는 변경 후 시간 상한을 실측했다고 주장하지 않는다. 실제 장애는 새 전용 Redis 컨테이너를 중단했으며 복구 시 자신의 키를 명시적으로 DEL했다. 재연결만으로 최신이라고 하지 않는다.

이 비교는 현재 Java 전체 후보 정렬 경로에 캐시를 붙인 결과다. 다른 DB 정렬 대안·로컬 캐시·Sorted Set과의 우열이나 모든 최악 상황을 검증한 결과는 아니다. 현재 좋아요순의 계약을 완화하는 주기 집계는 별도 정책 결정이 필요하다. [측정 원본](measurements/100k-likes-cache.json), [전체 검사·숫자 대조](measurements/likes-cache-validation.json).

재실행은 현재 JAR를 빌드한 뒤 아래처럼 새 출력 경로로 수행한다. --baseline은 기존 파일에 담긴 구현 전 기준선을 함께 보존하는 입력이며, 현재 DB-only 시간은 새로 측정한다. 다른 벤치마크와 겹치지 않게 실행한다.

```bash
python3 scripts/compare-week4-likes-cache.py \
  --jar apps/commerce-api/build/libs/commerce-api-a083b48.jar \
  --baseline docs/week4/measurements/100k-likes-cache.json \
  --output docs/week4/measurements/100k-likes-cache-repeat.json
```

## 29. 설계 문서의 읽는 순서 정리

design을 조회 계약·측정 방법 → 상품 인덱스 실험과 선택 근거 요약 → 좋아요 카운터 → 현재 조회 흐름 → 가격순 캐시 정책·비교·결론 → 좋아요순 캐시 비교 순으로 재배치했다. 목차와 절 링크를 추가하고 인덱스 실험의 1~8 번호는 유지했다. 비교 전 질문과 완료 결과를 하위 제목으로 구분하고, 실험별 추가·삭제·유지는 당시 기록임을 안내했다. 기존 9~13절의 내용은 해당 주제 제목 아래로 이동했으며 가격순·좋아요순 결론의 명시적 앵커를 유지했다.

기존 설명·정책·수치·결과를 유지하고 절 번호와 이동 안내에 해당하는 본문 5곳만 연결에 맞게 수정했다. 새로 추가된 좋아요순 실험 내용도 포함해 기존 코드 블록 8개와 표 11개가 그대로인지 대조했다. 추가한 표는 탐색용 목차 1개다. 상대 링크·앵커·코드 블록·공백과 측정 JSON 해시를 확인했다. 코드·인덱스·성능 원본 변경이나 새 측정은 없으며 테스트·Checkstyle·ArchUnit은 문서 재배치에서 재실행하지 않았다.

<a id="popular-cache-snapshot"></a>

## 30. 인기순 ID를 60초 재사용하고 하트·숫자는 현재 값으로 합성

순위의 잠시 늦은 반영을 허용하고 현재 숫자·하트는 DB에서 조회하기로 했다. ID 목록을 재사용해 무효화 횟수를 줄이고, Redis가 없어도 DB에서 다시 만들 수 있게 했다. 실제 구현 결정은 [ADR 0025](../adr/0025-popular-page-snapshot-with-ttl.md), 읽는 흐름과 정책은 [design](design.md#popular-cache-choice)에 모았다. 60초는 실험 시작값이며 최적 주기를 측정한 결과가 아니다.

### 변경과 회귀

- 새 정렬은 sort=popular, 브랜드 필수·page=0·size=20이며 지원 밖 조건은 400이다. 기존 likes_desc는 현재 카운터로 전체 후보를 정렬하는 DB 경로로 유지한다.
- PopularPage/PopularPageCache와 RedisPopularPageCache는 ID만 저장한다. TTL은 60초이며 hit나 좋아요 변경으로 연장하지 않는다. cache-aside이므로 만료 뒤 다음 요청이 재계산하며 배치가 아니다.
- LikeFacade에서 순위 변경 이벤트와 취소 후 브랜드 ID 추가 조회를 제거했다. 좋아요 관계·DB 카운터·기존 HTTP 201/200/204는 유지한다. 상품 생성/삭제·브랜드 삭제만 commit 후 PopularPageChanged로 해당 브랜드 키를 지운다.
- 상품·브랜드·카운터·개인 하트는 DB에서 합성한다. 숫자와 저장 순서의 일시적 불일치는 허용한 정책이다. 삭제·개인 정보·rollback·쓰기 트랜잭션 우회는 유지한다.
- 기존 commerce.cache.likes 설정/타입은 제거하고 commerce.cache.popular.enabled/ttl-seconds/prefix를 사용한다. 옛 키를 읽지 않으며 기본 prefix도 별도다. 추가 인덱스·테이블·Sorted Set·revision·집계 배치는 없다.

정책 테스트를 먼저 추가해 기존 코드의 sort=popular 요청이 400으로 실패함을 확인했다. 첫 통합 실행의 실패 1건은 spy의 상품 생성 준비 호출까지 eviction 검증에 포함한 문제였다. 준비 이후 호출 기록을 분리하고 전체 검사를 다시 실행했다. 기대값이나 lint/ArchUnit 규칙은 완화하지 않았다. 새 enum으로 기존 parameterized 검사 3건이 늘었고 인기순 시나리오 2건을 추가해 이전 326건에서 331건이 됐다. 기존 parameterized 요청도 popular의 브랜드·첫 페이지 조건에 맞췄다.

`:apps:commerce-api:check :apps:commerce-api:bootJar`가 성공했다. 전체 56 suites·331건, 실패/오류/skip 0이며 Checkstyle Main/Test와 ArchUnit을 포함한다. PopularPageCacheIntegrationTest 17건은 공유 ID와 사용자 분리, 실제/중복 등록·취소의 현재 하트·숫자와 TTL 유지, 페이지 밖 상품의 만료 후 진입, likes_desc 즉시 반영, 상품/브랜드 변경·삭제·rollback, 정렬 동점, 잘못된 조건/사용자, 손상 JSON, DB 오류, 늦은 채움, DEL 실패, 전용 Redis 중단/재시작을 확인했다. 만료를 짧게 조절한 통합 테스트와 기본 60초의 관찰은 구분한다.

이전 after-commit DEL 비교 스크립트는 해당 구현 JAR만 받도록 명시했다. 현재 재실행은 아래 스크립트로 새 출력 파일에 저장한다. 기존 seed의 전용 복제 DB를 쓰며 원본 데이터·인덱스를 바꾸지 않는다. 기존 단계의 JSON은 그대로 보관한다.

```bash
python3 scripts/compare-week4-popular-cache.py \
  --jar apps/commerce-api/build/libs/commerce-api-a083b48.jar \
  --output docs/week4/measurements/100k-popular-cache-repeat.json
```

### 현재 빌드의 실제 API 비교

JAR SHA-256은 `b0d02a53cc14fd863da44dc4123ddf70a6378a0edfaeaf82a284c746025c9d3b`다. 새 API 비교 전·후 검사에 사용하는 production 클래스와 JAR의 바이트를 대조했다. parameterized 요청 조건을 맞춘 뒤 최종 전체 check도 다시 성공했고 JAR 해시가 측정 때와 같은지 확인했다. 이전 ADR 0024 JAR/성능 값을 현재 결과로 재사용하지 않았다. 현재 결과와 전체 검사 집계는 [100k-popular-cache.json](measurements/100k-popular-cache.json) 하나에 보관하고 새 로그·중간 결과는 커밋 대상에서 제외한다.

기존 상품 10만 건·브랜드 7 활성 990개·인덱스·좋아요 카운터를 전용 DB에 복제했다. 사용자 95, popular·page=0·size=20이다. DB/Redis JVM 순서는 DB→Redis / Redis→DB / DB→Redis, Redis hit/miss 순서도 교대했다. 각 모드마다 워밍업 100/측정 200회다. HTTP 요청 시작부터 본문 수신까지를 재고 JSON 검증·키 제어·SQL 기록은 시간 밖에서 한다.

| 경우 | 1차 중앙값(ms) | 2차 중앙값(ms) | 3차 중앙값(ms) | 세 p95 범위(ms) |
| --- | ---: | ---: | ---: | ---: |
| 캐시 없음 | 15.218 | 15.139 | 17.222 | 19.193~21.053 |
| Redis miss | 16.073 | 16.834 | 17.810 | 19.544~21.191 |
| Redis hit | 11.170 | 9.237 | 11.513 | 11.741~14.267 |

miss는 자신의 키를 매 GET 전에 DEL하며 그 제어 시간은 GET 측정 밖이다. hit는 모드 시작에 키를 비우고 한 번 채운 뒤 변경 없이 반복했다. 대표 SQL은 시간 측정과 분리했다.

### 실제 좋아요 변경 중 재사용

캐시 안의 상품 64107를 사용자 95가 POST(201)/DELETE(204)로 번갈아 바꿨다. 이 상품은 좋아요 하나로 현재 정렬 위치가 바뀌므로 기존 순위와 현재 숫자를 분리했는지 확인할 수 있다. 초기 시도는 같은 수 구간의 이미 선두인 상품을 골라 순위 차이가 없다는 사전 assertion에서 멈췄다. DB·키를 만들기 전이었으며, 독립 seed 계산에서 실제 순서가 바뀌는 상품을 골라 전체 비교를 실행했다.

각 패턴도 워밍업 100 GET/측정 200 GET이다. DB-only는 현재 정렬, Redis는 저장된 ID 순서와 현재 숫자·하트를 각각 독립 기대값으로 검증했다. 바뀐 순서까지 동일하다고 주장하지 않는다.

| 조건 | 캐시 없음 GET 중앙값(ms) | Redis GET 중앙값(ms) |
| --- | ---: | ---: |
| 변경 1회 / GET 10회 | 14.031~15.868 | 8.289~11.488 |
| 매 GET 전 변경 | 12.792~14.620 | 7.091~8.633 |

| 조건 | 캐시 없음 변경 API 중앙값(ms) | Redis 변경 API 중앙값(ms) |
| --- | ---: | ---: |
| 변경 1회 / GET 10회 | 7.581~8.297 | 7.743~9.738 |
| 매 GET 전 변경 | 6.557~7.207 | 5.650~7.309 |

변경 API 표본은 POST/DELETE를 합친 전체 HTTP 시간이다. 각 반복에서 1:10 패턴은 측정 20개, 1:1은 200개다. 현재 LikeFacade는 두 모드 모두 Redis 명령이나 취소 후 브랜드 ID 추가 조회를 수행하지 않는다. 표의 차이를 Redis DEL 시간으로 해석하지 않는다.

Redis의 두 혼합 패턴 모두 GET 뒤 저장 ID가 같은지 확인했고 TTL이 줄어들었다. 시간 측정에 넣지 않은 키 대조 1,800회다. 이는 개별 GET의 내부 hit 로그나 운영 hit rate가 아니다. 혼합 뒤 대표 SQL에서도 활성 ID 경로를 확인했다.

### SQL·값·동시 조회

대표 SQL 총 21개 요청은 브랜드·상품·카운터·사용자·개인 관계 SELECT 5개였다. 상품 총수 COUNT는 0이다. 순차 DB-only/miss와 혼합 DB-only는 상품/카운터 각각 990행, hit와 혼합 Redis는 각각 20행이었다. 개인 관계는 각 페이지 ID 20개를 대상으로 조회했다. 개인 관계의 반환 행 수가 20이라는 뜻은 아니다. 원본 전체 정렬은 Java comparator이므로 DB 계획의 Sort 유무로 이 비용을 판단하지 않는다.

캐시 값은 ids 한 필드·ID 20개·128바이트였다. 전용 키의 Redis MEMORY USAGE는 세 반복 모두 280바이트였다. 한 키의 관찰값이며 Redis 인스턴스 전체 메모리나 월 비용을 뜻하지 않는다.

Python 스레드 20개를 barrier에서 함께 출발시키고 준비용 3묶음 뒤 10묶음×20 GET, 모드마다 세 번 반복했다. DB 풀 최대는 40이다. 서버 SQL 시작 시점까지 같게 강제하지 않았다. 빈 캐시는 묶음 직전에 자신의 키를 지운 상태이며 모든 요청이 miss임을 뜻하지 않는다.

| 시작 상태 | 세 p95 범위(ms) |
| --- | ---: |
| 캐시 없음 | 66.157~106.041 |
| 시작 시 빈 Redis | 72.790~98.135 |
| 미리 채운 Redis | 27.832~36.470 |

측정 GET은 순차 1,800·변경 혼합 2,400·동시 1,800, 합계 6,000개다. 전 응답이 각 정책의 기대값과 같았고 동시 오류는 0이다. 실제 측정 변경 API는 1,320회다. 준비 요청과 만료 확인은 이 GET 수에 포함하지 않는다. 이전 8개 제출 JSON의 해시, 원본 데이터·인덱스와 카운터 checksum 보존, 변경한 관계/카운터 복원과 자신이 만든 DB·권한·키·앱 정리를 확인했다.

### 실제 60초 만료 관찰과 판단

마지막 Redis JVM에서 키를 비우고 기본 설정으로 채웠다. 첫 관찰 PTTL은 59995ms였다. 목록 안 상품과 밖 상품 307에 좋아요를 등록해도 ID 값은 유지됐고, 숫자와 내 하트는 현재 값으로 응답했다. 같은 시점 likes_desc는 바뀐 현재 순서를 반환했다. 좋아요 뒤 PTTL은 59948ms로 연장되지 않았다.

키 존재를 250ms 간격으로 확인해 첫 GET 시작부터 약 60.136초 뒤 키가 없음을 관찰했다. 관찰 간격과 첫 DB 조회 시간이 포함되므로 정확한 Redis 만료 시각을 재는 실험은 아니다. 그 뒤 첫 popular 요청은 두 변경을 반영한 전체 후보 정렬과 같았고 페이지 밖 상품도 들어왔다. 두 좋아요는 취소해 복원했다.

좋아요마다 DEL하던 방식 대신 순위 지연을 허용해 재사용했다는 점이 이번 선택의 근거다. 모든 요청의 변경 후 60초 절대 상한, 운영 hit rate·최적 TTL·최대 처리량·DB CPU/연결 대기 감소까지 증명한 것은 아니다. 만료 시 중복 원본 조회 비용도 남아 있다. 이후 필요성이 관찰되면 요청 합치기나 갱신 방식을 검토한다. Sorted Set·로컬 추가·배치·revision은 현재 도입하지 않았다.

<a id="final-review-fixes"></a>

## 31. 최종 점검: 첫 페이지 범위 상수화·사용자 확인 순서·브랜드 없는 목록 측정

**첫 페이지 범위 상수화.** 캐시 대상 "첫 페이지 20개"가 `PricePageReader`(채우기 조건), `PricePageInvalidator`(삭제 대상), `ProductQueryService`(인기순 요청 검증), `RedisPopularPageCache`(값 검증·키) 네 곳에 숫자로 따로 있었다. 한 곳만 바뀌면 바뀐 페이지의 캐시가 쓰기 후에도 지워지지 않을 수 있어, `domain.product.CachedFirstPage`(PAGE=0, SIZE=20, `matches`)로 모았다. 인프라가 application을 참조하지 않도록 캐시 포트와 같은 domain 패키지에 두었다. Redis 키 문자열은 이전과 같다.

**사용자 확인 순서.** `ProductListQueryService`가 공통 목록(가격순이면 캐시 채우기 포함)을 먼저 만든 뒤 `X-USER-ID` 사용자를 확인해, 없는 사용자도 목록 조회를 다 한 다음 404가 났다. 사용자 확인을 맨 앞으로 옮겼다. 응답 계약(없는 사용자 404, 헤더 없음은 공개 목록)은 같다. `ProductListQueryServiceTest`의 해당 테스트를 "목록 조회 전에 거절하고 상품 조회를 하지 않는다"로 바꿨다.

전체 `:apps:commerce-api:check` 331건, 실패·오류·skip 0, Checkstyle·ArchUnit 통과.

**브랜드 없는 목록 측정(과제 측정 대상 밖).** 기본 정렬 LATEST는 브랜드가 없으면 활성 상품 전체를 엔티티로 읽고, 전체 ID로 카운터를 조회한 뒤 Java에서 정렬한다. 위 수정 후 JAR로 `loopers_benchmark_100k`(활성 96,990개)에 실제 API를 띄워 순차 측정했다(케이스별 준비 5회·측정 20회, 헤더 없음, 브랜드 없는 경로는 캐시 대상 아님).

| 요청 | 중앙값 | p95 |
| --- | ---: | ---: |
| 브랜드 없음·latest(기본 요청) | 573.03ms | 589.70ms |
| 브랜드 없음·likes_desc | 596.58ms | 636.92ms |
| 브랜드 없음·price_asc | 127.51ms | 133.52ms |
| 브랜드 7·latest | 16.42ms | 19.00ms |
| 브랜드 7·price_asc | 9.96ms | 11.53ms |

같은 DB에서 활성 상품 전체·카운터 전체를 SQL로만 읽는 데는 각각 약 28ms·74ms였다. 기본 요청의 나머지 시간은 약 9.7만 개 엔티티 생성·카운터 IN 조회·Java 정렬에서 생긴 것으로 보지만 구간별로 나눠 재지는 않았다. 브랜드 없는 price_asc는 현재 인덱스가 brand_id로 시작해 정렬 인덱스를 쓰지 못한다. 이번 과제의 측정·개선 대상은 브랜드 필터 경로이므로 코드는 바꾸지 않고 한계로 남긴다. 원본은 로컬 `measurements/100k-brandless-list.json`이며 커밋 대상 묶음에는 넣지 않았다.

**캐시와 주문 금액 검증 추가.** 과제 4단계의 "관리자 가격 수정 후 과거 주문 금액 유지", "캐시에 담긴 브랜드 일괄 삭제 후 다른 브랜드·과거 주문 유지"를 캐시를 켠 상태로 확인하는 테스트가 없어 `PricePageCacheIntegrationTest`에 세 개를 추가했다.

- `adminPriceChangeWithCachedPageKeepsPastOrderAmountAndNewOrderUsesNewPrice`: 캐시를 채운 뒤 관리자 API로 가격을 1000 → 500으로 바꾸면 목록은 500을 보여주고, 그 전에 만든 주문은 단가 1000·합계 2000을 유지하며, 새 주문은 500으로 기록된다.
- `staleCachedPriceAfterFailedEvictionIsNeverUsedForNewOrders`: 캐시 삭제를 실패시켜 목록에는 옛 가격 1000이 남은 상태에서도 새 주문 단가는 DB 가격 500이다. 주문은 캐시의 표시 가격을 쓰지 않는다는 ADR 0021의 정책을 직접 확인한다.
- `brandDeletionWithCachedPagesKeepsPastOrdersAndOtherBrandPage`: 두 브랜드의 캐시를 채운 뒤 한 브랜드를 지우면 그 키는 지워지고, 다른 브랜드 페이지는 그대로이며, 지워진 브랜드 상품의 과거 주문 단가·상품명은 유지된다.

전체 `:apps:commerce-api:check` 334건, 실패·오류·skip 0, Checkstyle·ArchUnit 통과.

<a id="name-covering-write"></a>

## 32. 이름 포함 가격순 인덱스의 쓰기 비용: 1만·10만 건 비교

이름을 포함한 커버링 후보의 상품 쓰기 비용을 추가로 확인했다. 기존 13절의 가격순 인덱스 추가 전후 상품 쓰기, 15절 `LikeCounterExperimentTest`의 관계 집계/카운터 방식 좋아요 쓰기는 이미 측정한 별도 실험이다. 이번에는 기존 가격순 인덱스에 **name을 추가하는 차이**만 비교하며, 이전 수치·SQL·측정 원본을 바꾸지 않는다.

### 데이터·인덱스·측정 조건

- MySQL 8.0.46·InnoDB·REPEATABLE READ, buffer pool 128MiB, innodb_page_size 16KiB, innodb_flush_log_at_trx_commit=1·sync_binlog=1·log_bin=1.
- 같은 `loopers_benchmark_100k.product`의 id 1~10,000 및 1~100,000을 각각 복사했다. 기존 1만 건 DB를 다시 seed한 것이 아니며, 이름 폭과 생성 공식도 같은 원본을 따른다.
- 무작위 이름의 전용 임시 DB 안에 복제 테이블을 만들었다. 두 경우 모두 기존 `(brand_id, deleted_at)` 인덱스를 유지하고, 가격순 인덱스만 `(brand_id, deleted_at, price ASC, created_at DESC, id DESC)` 또는 여기에 name을 붙인 구성으로 둔다. 후보를 기존 가격순 인덱스와 동시에 추가 유지하는 비교가 아니다.
- 매 회차 테이블을 새로 만들고, 동일한 PK 순서로 데이터를 적재한 뒤 가격순 인덱스를 생성하고 ANALYZE했다. 3회 반복, 경우·건수·작업별 준비 50회/측정 200회, 단일 클라이언트다. 25개 작업마다 두 경우의 실행 순서를 교대하고, 건수 순서와 작업 순서도 회차별로 바꿨다.
- 등록은 새로운 명시적 ID를 사용한다. 이름·가격 변경과 논리 삭제는 같은 난수 seed로 뽑은 서로 다른 활성 상품에 한 번씩 적용한다. 이름은 길이를 유지하며 실제 값을 바꾸고, 가격은 +1, 논리 삭제는 NULL에서 시각 값으로 바꾼다. 모든 DML을 개별 commit한다.
- START TRANSACTION·DML·COMMIT의 MySQL 서버 프로파일 합계와 DML/COMMIT 각각의 시간을 보관했다. Docker·클라이언트 왕복·HTTP·애플리케이션 검증은 포함하지 않는다. 데이터 복구·DDL·결과 검증도 시간 밖이다. 초당 처리량·동시 경합을 측정한 실험이 아니다.
- 작업별로 변경한 모든 상품의 기대 필드를 대조하고, 복구 후 전체 행 수·ID/가격/재고 합계·전체 컬럼의 CRC 기반 지문을 원본 부분집합과 대조했다. 행을 복구해도 물리 페이지 배치까지 원상복구되는 것은 아니다.

### 커밋을 포함한 SQL 쓰기 시간

아래 범위는 세 회차 통계의 최솟값~최댓값이다. 변화율은 같은 회차의 `(이름 포함 중앙값 / 기존 중앙값 - 1) × 100`이며 서로 다른 회차 범위의 끝값끼리 나눈 값이 아니다. 신뢰구간이나 운영 성능의 확정 악화율로 해석하지 않는다.

| 건수 | 작업 | 기존 중앙값(ms) | 이름 포함 중앙값(ms) | 회차별 중앙값 변화율 |
| --- | --- | ---: | ---: | ---: |
| 1만 | 상품 등록 | 1.159~1.281 | 1.206~1.269 | -1.0~+4.1% |
| 1만 | 이름 변경 | 1.246~1.306 | 1.268~1.407 | +1.8~+7.7% |
| 1만 | 가격 변경 | 1.263~1.412 | 1.250~1.363 | -3.5~-0.8% |
| 1만 | 논리 삭제 | 1.261~1.465 | 1.297~1.443 | -1.5~+2.8% |
| 10만 | 상품 등록 | 1.252~1.269 | 1.175~1.272 | -7.4~+0.9% |
| 10만 | 이름 변경 | 1.244~1.280 | 1.336~1.506 | +7.5~+19.7% |
| 10만 | 가격 변경 | 1.296~1.456 | 1.375~1.434 | -5.4~+6.1% |
| 10만 | 논리 삭제 | 1.312~1.356 | 1.314~1.343 | -1.0~+0.1% |

이름 변경에서는 DML 자체의 중앙값도 1만 건에서 0.111~0.112 → 0.130~0.149ms, 10만 건에서 0.100~0.105 → 0.147~0.164ms로 늘었다. 이름을 인덱스에도 유지하면 추가 갱신이 필요한 구조와 맞지만, 증가 시간을 내부 처리별로 분해한 결과는 아니다. COMMIT 시간의 변동도 있으므로 트랜잭션 전체 차이를 순수 인덱스 갱신 시간으로 취급하지 않는다.

이름 변경의 p95는 1만 건에서 기존 1.801~2.367ms·이름 포함 1.916~2.201ms, 10만 건에서 기존 1.848~2.200ms·이름 포함 2.212~2.524ms였다. 1만 건에서는 p95가 짧아진 회차도 있으므로 모든 지연 지표가 같은 비율로 악화됐다고 결론 내리지 않는다. 원시 표본과 각 회차의 p95는 JSON에 보관했다.

### 할당량과 읽기 이득의 범위

가격순 인덱스 자체의 준비 직후 할당 페이지는 세 회차 모두 1만 건에서 25 → 97페이지(400 → 1,552KiB), 10만 건에서 289 → 419페이지(4,624 → 6,704KiB)였다. 페이지 할당 통계이며 정확한 유효 키 바이트나 같은 비율의 메모리/쓰기 성능 증가가 아니다.

EXPLAIN FORMAT=JSON으로 `id, brand_id, name, price, created_at`의 고정 SQL은 이름 포함 후보에서 커버링이고, 현재 API의 상품 엔티티 컬럼(quantity·updated_at 추가)을 조회하면 두 후보 모두 커버링되지 않음을 확인했다. API 쿼리/응답·상품 인덱스는 변경하지 않았다. 실제 API의 projection을 바꾼 후 읽기 이득을 확인하는 작업은 남아 있다.

이번 조건에서는 이름 변경의 추가 비용을 확인했지만 모든 상품 쓰기의 일관된 악화는 확인하지 못했다. 10만 건에서 이름 변경 중앙값의 증가폭이 더 컸더라도 고정된 규모별 증가율이나 더 큰 데이터의 성능을 보장하지 않는다. 테이블을 적재·스캔한 단일 클라이언트 실험이며 buffer pool 압박·지속 부하·동시 요청·최대 처리량·실제 읽기/쓰기 요청 비율은 측정하지 않았다. 기존 선택을 뒤집을 API 읽기 이득의 근거는 추가하지 않는다.

### 검증과 재현

[실행기](../../scripts/compare-week4-covering-write.py), 새 원본(`measurements/covering-write-10k-100k.json`). 기존 제출 근거 9개 허용 목록은 변경하지 않았다.

```bash
DOCKER_CONTEXT=desktop-linux python3 scripts/compare-week4-covering-write.py \
  --output docs/week4/measurements/covering-write-10k-100k.json
```

기존 출력 경로는 거절하므로 재실행할 때는 새로운 파일명을 사용한다. Python 구문 검사와 1만·10만 건의 소량 사전 실행(준비 2회·측정 4회·1회차)에서 기대 변경·전체 복구·정리를 확인했다. 기존 출력 덮어쓰기와 잘못된 표본 수 거절도 확인했다. 본 측정은 준비 2,400건·측정 9,600건의 개별 commit을 실행했으며, 모든 경우의 기대 변경과 복구가 일치했다. 원본 상품의 전체 컬럼 지문·스키마·인덱스 정의, 기존 측정 JSON 63개의 SHA-256이 전후 같고, 자신이 만든 임시 DB가 제거됐음을 확인했다.

애플리케이션 Java 코드는 바꾸지 않았으며 이번 추가 실험에서 Java 테스트·Checkstyle·ArchUnit은 재실행하지 않았다. 31절의 334건은 해당 구현 검증 기록으로 유지한다.

<a id="price-cache-db-time"></a>

## 33. 가격순 캐시의 요청당 DB 작업량: 캐시 없음·hit·miss

가격순 캐시가 응답 시간을 줄이지 못한 상태에서 **DB가 하는 일**은 줄이는지 확인했다. 기존 비교는 API 시간과 SQL 개수(모든 경우 업무 SELECT 5개)까지였고, 같은 개수의 문장이 DB에서 얼마나 실행 시간과 CPU를 쓰는지는 재지 않았다.

### 조건

- 현재 커밋 7d91aac로 빌드한 `commerce-api-7d91aac.jar`, benchmark 프로필, 일반 10만 건 원본을 임시 DB로 복사했다(brand·product·product_like_count·users·product_like). 브랜드 7·price_asc·page=0·size=20·`X-USER-ID: 95`. 인기순 캐시는 끈다.
- 경우별 준비 100회 뒤 `performance_schema.events_statements_summary_by_digest`를 비우고 순차 GET 200회를 실행했다. 임시 DB 스키마의 문장만 합산한다. 실행 시간은 문장별 서버 실행 시간(SUM_TIMER_WAIT), CPU는 `events_statements_cpu`를 측정 중에만 켠 SUM_CPU_TIME이다. 트랜잭션 SET·COMMIT도 포함한다.
- miss는 GET마다 직전에 자신의 Redis 키를 지운다(관리자 쓰기 없음, HTTP 시간 밖). 키 삭제를 `docker compose exec redis-cli`로 하므로 요청 사이에 공백이 생긴다. 이 공백의 영향을 분리하려고 캐시 없음에도 같은 삭제 호출을 넣은 `db_only_gap`을 함께 쟀다.
- 앱 실행 순서를 (DB, Redis) → (Redis, DB) → (DB, Redis)로 바꿔 세 번 반복했다. 매 응답은 seed 기대값(상품·순서·가격·좋아요 수·isLiked)과 같았다.

### 결과 (요청 하나당)

| 경우 | DB 문장 | 읽은 행 | DB 실행 시간(ms) | DB CPU(ms) | 상품 문장 실행 시간(ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 캐시 없음 (연속) | 10 (SELECT 5) | 54 | 1.221~1.242 | 1.208~1.232 | 0.332~0.345 |
| Redis hit (연속) | 10 (SELECT 5) | 54 | 1.167~1.230 | 1.157~1.219 | 0.289~0.342 |
| 캐시 없음 (miss와 같은 공백) | 10 (SELECT 5) | 54 | 1.440~1.505 | 1.426~1.492 | 0.382~0.424 |
| Redis miss | 10 (SELECT 5) | 54 | 1.474~1.498 | 1.460~1.486 | 0.397~0.403 |

같은 회차 비교로 hit는 캐시 없음보다 DB 실행 시간이 -6.1%·-2.8%·+0.8%, miss는 같은 공백의 캐시 없음보다 +3.5%·-1.1%·-0.4%였다.

### 해석

- 세 경우의 차이는 상품 문장 하나뿐이다. 캐시 없음·miss는 가격순 인덱스로 상품 20개를 고르고(20행), hit는 캐시의 20개 ID가 활성인지 확인한다(PK로 20행). 읽는 행 수가 같아 hit가 아끼는 DB 시간은 요청당 약 0~0.06ms, 전체 DB 시간의 0~6%였다.
- 나머지 네 SELECT(브랜드·사용자·좋아요 카운터·내 좋아요)와 트랜잭션 문장은 공통 캐시에 넣지 않아 hit여도 그대로다. 요청당 DB 시간의 대부분이 여기서 나온다.
- miss가 연속 캐시 없음보다 약 20% 비싸 보인 것은 캐시 작업이 아니라 요청 사이 공백 때문이었다. 같은 공백을 준 캐시 없음과 비교하면 차이가 회차마다 부호가 바뀌는 수준이다. 따라서 miss가 DB에 추가로 시키는 일은 이번 조건에서 확인되지 않았다.
- 결론적으로 이 경로의 가격순 캐시는 hit율이 100%여도 DB 작업을 최대 몇 % 정도만 줄인다. [design.md의 가격순 캐시 결론](design.md#price-cache-choice)(운영 적용 시 캐시 없는 DB 조회 우선 검토)을 바꾸지 않는다.

### 한계

순차 단일 클라이언트이며 동시 부하·지속 부하·서버 전체 CPU·최대 처리량은 재지 않았다. 문장별 CPU는 MySQL이 문장 실행에 쓴 시간이며 컨테이너 전체 CPU가 아니다. 로컬 한 대에서 앱·Redis·MySQL이 CPU를 나눠 쓴다. 브랜드 7 첫 페이지 하나만 측정했다.

### 재현과 검증

[실행기](../../scripts/measure-week4-price-cache-db-time.py), 원본(`measurements/100k-price-cache-db-time.json`). 소량 사전 실행(준비 5·측정 20)으로 동작을 확인한 뒤 본 측정을 실행했다. 원본 DB의 스냅샷이 전후 같고, 임시 DB·권한·Redis 키를 정리했으며, `events_statements_cpu` 설정을 원래 값(NO)으로 되돌렸다.

```bash
DOCKER_CONTEXT=desktop-linux python3 scripts/measure-week4-price-cache-db-time.py \
  --jar apps/commerce-api/build/libs/commerce-api-7d91aac.jar \
  --output docs/week4/measurements/100k-price-cache-db-time.json
```

애플리케이션 Java 코드는 바꾸지 않았다.

<a id="popular-cache-db-time"></a>

## 34. 인기순 캐시의 요청당 DB 작업량: 캐시 없음·hit·miss

33절과 같은 실행기·조건으로 `sort=popular`를 비교했다(`--sort popular`). 캐시 없음은 인기순 캐시를 끈 앱, Redis는 인기순 캐시만 켠 앱이다(가격순 캐시는 둘 다 끔). miss는 GET마다 인기순 키를 지우며, 같은 공백을 준 캐시 없음(`db_only_gap`)과 비교한다. 매 응답은 seed로 계산한 현재 좋아요순·좋아요 수·isLiked와 같았다.

### 결과 (요청 하나당)

| 경우 | DB 문장 | 읽은 행 | DB 실행 시간(ms) | DB CPU(ms) | API 중앙값(ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 캐시 없음 (연속) | 10 (SELECT 5) | 1,996 | 4.511~4.688 | 4.500~4.675 | 17.865~18.109 |
| Redis hit (연속) | 10 (SELECT 5) | 56 | 1.119~1.199 | 1.108~1.187 | 11.680~12.232 |
| 캐시 없음 (miss와 같은 공백) | 10 (SELECT 5) | 1,996 | 4.733~4.894 | 4.718~4.879 | 19.053~19.827 |
| Redis miss | 10 (SELECT 5) | 1,996 | 4.795~5.025 | 4.772~5.007 | 19.958~21.784 |

같은 회차 비교로 hit는 캐시 없음보다 DB 실행 시간이 -75.5%·-74.0%·-74.4%, miss는 같은 공백의 캐시 없음보다 +1.3%·+4.6%·0.0%였다. API 시간은 참고값이다.

### 해석

- 캐시 없음·miss는 브랜드 7의 활성 상품 990개와 그 카운터 990개를 읽어 순위를 계산한다. hit는 캐시의 20개 ID로 상품 20개·카운터 20개만 읽는다. 문장 수는 같지만 읽는 행이 1,996개에서 56개로 줄어 DB 시간이 약 3/4 줄었다.
- miss가 DB에 추가로 시키는 일은 회차마다 0~5% 수준이다. hit가 아끼는 시간(약 3.3~3.5ms)과 비교한 손익분기 히트율은 0.0~6.2%다. 60초 TTL에서는 브랜드 첫 페이지마다 만료 뒤 첫 요청만 miss이므로, 1분에 두 번 이상 조회되면 손익분기를 넘는다.
- 33절의 가격순(hit가 DB 시간 0~6% 감소)과 대비된다. 원본이 인덱스로 이미 20행만 읽는 경로에서는 캐시가 DB 일을 거의 덜지 못하고, 원본이 990행을 읽고 정렬하는 경로에서는 크게 던다. [ADR 0025](../adr/0025-popular-page-snapshot-with-ttl.md)의 순위 재사용 선택과 맞는 결과다.

### 한계와 재현

33절과 같다. 순차 단일 클라이언트이며 만료 순간 동시 miss의 중복 계산(ADR 0025 한계 3)과 서버 전체 CPU는 재지 않았다. 원본(`measurements/100k-popular-cache-db-time.json`). 원본 DB 스냅샷이 전후 같고 임시 DB·권한·Redis 키·`events_statements_cpu` 설정을 정리·복원했다.

```bash
DOCKER_CONTEXT=desktop-linux python3 scripts/measure-week4-price-cache-db-time.py --sort popular \
  --jar apps/commerce-api/build/libs/commerce-api-7d91aac.jar \
  --output docs/week4/measurements/100k-popular-cache-db-time.json
```

<a id="popular-cache-growth-stampede"></a>

## 35. 인기순: 데이터가 커질 때와 만료 순간 (변경 전 코드)

34절의 결론("원본이 비쌀 때만 캐시가 DB 일을 크게 던다")이 데이터가 커질 때도 유지되는지, 그리고 ADR 0025 한계 3(만료 순간 동시 요청의 중복 계산)이 실제로 어떤 비용인지 확인했다. 이 절의 숫자는 모두 앱이 브랜드 상품 전체를 읽어 정렬하던 **변경 전 코드**의 결과다. 36절에서 바꾼 뒤 다시 쟀다.

### 조건

- 33·34절과 같은 실행기에 두 옵션을 더했다. `--brand-skew`는 임시 DB에서 ID 1~90,000의 brand_id를 7로 바꿔 브랜드 7의 활성 상품을 990개에서 87,390개로 만든다(가격순 쏠림 실험과 같은 방식). `--stampede`는 순차 측정 대신 스레드 N개를 barrier로 함께 출발시킨 묶음을 보내고, 묶음 동안 브랜드 전체를 읽는 상품 문장의 실행 횟수(= 순위 재계산 횟수)를 센다.
- 묶음 상태: 캐시 없음(인기순 캐시를 끈 앱), cold(묶음 직전에 키 삭제), warm(묶음 직전에 키 채움). 준비 2묶음 뒤 측정 묶음을 세었다. 앱 힙은 지금까지의 모든 측정과 같은 `-Xmx512m`이다. HTTP 500·30초 무응답·연결 끊김은 실패로 센다.
- 매 성공 응답은 seed로 계산한 기대값(쏠림 DB는 바뀐 브랜드 기준)과 같았다.
- 맥북이 측정 중 잠들어 결과가 오염된 첫 실행은 버리고, 이후 실행은 `caffeinate -i`로 잠자기를 막았다.

### 데이터가 커지면: 요청당 DB 작업 (브랜드 7 = 87,390개, 순차, 준비 10·측정 50, 3회)

| 경우 | 읽은 행 | DB 실행 시간(ms) | API 중앙값(ms) |
| --- | ---: | ---: | ---: |
| 캐시 없음 | 187,406 | 181.2~185.2 | 532.5~544.9 |
| Redis hit | 56 | 1.11~1.14 | 11.7~12.4 |
| Redis miss | 187,406 | 181.6~183.4 | 535.7~536.1 |

hit는 같은 회차의 캐시 없음보다 DB 실행 시간이 99.4% 적었다. miss는 같은 공백을 준 캐시 없음과 -2.4~+0.8%였다. 34절(990개)의 감소폭 74~76%보다 커졌다. 원본은 브랜드 상품 수에 비례해 커지고 hit는 56행으로 그대로라, 데이터가 커질수록 캐시가 덜어 주는 몫이 커진다.

### 만료 순간: 정상 데이터 (브랜드 7 = 990개, 측정 10묶음, 3회)

| 상태 | 동시 | 순위 재계산 / 묶음 | DB 실행 시간 / 묶음(ms) | p50(ms) | p99(ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 캐시 없음 | 20 | 19~20 | 93~180 | 72.9~75.6 | 125.4~144.5 |
| 캐시 없음 | 80 | 79~80 | 461~843 | 189.5~211.8 | 274.2~287.2 |
| cold | 20 | 9~20 (평균 13.9) | 57~176 | 68.8~73.0 | 101.9~112.8 |
| cold | 80 | 8~22 (평균 15.1) | 123~211 | 108.7~126.8 | 161.7~175.9 |
| warm | 20 | 0 | 20~41 | 31.8~33.2 | 43.5~51.3 |
| warm | 80 | 0 | 93~133 | 67.7~74.4 | 121.1~177.2 |

실패는 없었다. 키가 빈 순간에 들어온 요청은 첫 저장 전까지 각자 순위를 다시 계산했다(묶음당 8~22번). 첫 저장 뒤 요청은 hit가 되어 동시 80에서도 평균 15번 정도에서 멈췄다.

### 만료 순간: 쏠림 데이터 (브랜드 7 = 87,390개, 측정 5묶음)

| 상태 | 동시 | 실패 / 요청 | p50(ms) |
| --- | ---: | ---: | ---: |
| 캐시 없음 | 5 | 0 / 75 | 1,420~1,483 |
| 캐시 없음 | 20 | **271 / 300** | 8,177~12,339 |
| cold | 5 | 0 / 50 | 1,421~1,726 |
| cold | 20 | **175 / 200** | 6,736~15,492 |
| warm | 5 | 0 / 50 | 17 |
| warm | 20 | **100 / 200** (1회차 0, 2회차 전부) | 35 (1회차) |

- 동시 20에서 요청마다 상품 87,390개를 엔티티로 올리고 87,390개의 카운터를 조회해 정렬하자 앱 로그에 `java.lang.OutOfMemoryError: Java heap space`가 남았고 응답은 500·무응답이 됐다.
- 2회차 warm은 키가 차 있었는데도 전부 실패했다. 직전 cold 묶음의 메모리 부족에서 앱이 회복하지 못한 상태였다. 3회차 Redis 구간에서는 앱이 연결을 끊고 종료되어 측정이 중단됐다(cold 20까지 2회분만 기록).
- 즉 캐시가 평소 응답을 17~35ms로 지켜도, 키가 비는 순간 동시 요청이 몰리면 원본 계산이 앱 메모리를 넘어 캐시가 있는 경로까지 무너졌다. 앱 힙을 키우면 실패하는 동시 수가 뒤로 밀릴 뿐이므로 구조를 바꿨다(36절).

### 재현

[실행기](../../scripts/measure-week4-price-cache-db-time.py), 원본(현재 로컬 보관): 쏠림 요청당(`measurements/100k-popular-cache-db-time-skew.json`), 정상 스탬피드(`measurements/100k-popular-cache-stampede.json`), 쏠림 스탬피드(`measurements/100k-popular-cache-stampede-skew-before.json`). 원본 DB 스냅샷이 전후 같았고 임시 DB·권한·Redis 키·`events_statements_cpu` 설정을 정리·복원했다(쏠림 스탬피드는 앱 종료로 중단됐지만 정리 단계는 실행됐다).

```bash
DOCKER_CONTEXT=desktop-linux caffeinate -i python3 scripts/measure-week4-price-cache-db-time.py --sort popular \
  --brand-skew --stampede 5,20 --bursts 5 \
  --jar apps/commerce-api/build/libs/commerce-api-<hash>.jar --output docs/week4/measurements/<new-file>.json
```

<a id="likes-db-sorted-page"></a>

## 36. 좋아요순·인기순 원본을 DB 정렬로 바꾼 뒤

[ADR 0026](../adr/0026-likes-ranking-sorted-in-database.md)에 따라 likes_desc·popular의 원본 경로를 바꿨다. 캐시 구조·API 응답·좋아요 쓰기는 그대로다.

### 바꾼 것

- `ProductRepository.findVisibleIdsByLikesDesc(brandId, page, size)`: 활성 상품·활성 브랜드를 `좋아요 수 DESC → 생성 시각 DESC → ID DESC`로 정렬해 한 페이지의 ID만 돌려준다. 카운터는 `LEFT JOIN`하고 정렬에 `coalesce(like_count, 0)`을 쓴다. 브랜드가 없으면 브랜드 조건을 빼고, page×size가 int를 넘으면 빈 목록이다.
- `ProductQueryService`: likes_desc·popular는 ID 페이지 → 상품 `findActiveByIds` → 기존 `enrich`(브랜드·좋아요 수) 순으로 읽는다. 인기순 hit 검사와 miss 저장은 그대로다. 앱에서 정렬하는 경로는 최신순만 남아, 쓰이지 않게 된 정렬 분기를 정리했다.
- 결과가 같도록 맞춘 규칙: 동점 규칙을 앱 비교기와 같게 했고, 삭제 브랜드를 SQL에서 걸러 페이지가 덜 차지 않게 했다. 같은 읽기 트랜잭션의 스냅샷에서 ID와 상품을 읽는다.
- 달라진 점: 카운터 누락은 예전에는 후보 전체 중 하나라도 누락되면 예외였고, 이제는 **반환된 페이지에 누락된 상품이 있을 때** 기존 좋아요 수 조회의 예외로 드러난다.

### 테스트

- 새 `ProductLikesOrderIntegrationTest` 3건: 동점 규칙과 두 번째 페이지, 삭제 브랜드·삭제 상품을 거른 뒤에도 브랜드 없는 페이지가 차는지, 반환 페이지의 카운터 누락 예외.
- 기존 기대값은 바꾸지 않았다. `ProductQueryServiceTest`는 같은 결과(2번 상품·좋아요 3)를 새 협력 메서드로 검증하고 전체 상품을 읽지 않음을 확인한다. `PopularPageCacheIntegrationTest`의 세 곳("hit에서 순위를 재계산하지 않음", "miss의 DB 오류 전파")은 같은 의도를 새 메서드로 확인한다. likes_desc 순서를 확인하던 기존 통합 테스트(`LikeCounterIntegrationTest`, 인기순·가격순 캐시 테스트)는 수정 없이 통과했다.
- `:apps:commerce-api:check` 57 suites·337건, 실패·오류·skip 0, Checkstyle·ArchUnit 통과.

### 다시 잰 결과 (같은 조건, 새 JAR)

요청당 DB 작업(순차, 3회):

| 데이터 | 경우 | 변경 전 읽은 행 / DB(ms) / API(ms) | 변경 후 읽은 행 / DB(ms) / API(ms) |
| --- | --- | --- | --- |
| 정상 (990개) | 캐시 없음 | 1,996 / 4.51~4.69 / 17.9~18.1 | 2,056 / 2.33~2.36 / 11.3 |
| 정상 (990개) | hit | 56 / 1.12~1.20 / 11.7~12.2 | 56 / 1.05~1.08 / 10.3~10.5 |
| 쏠림 (87,390개) | 캐시 없음 | 187,406 / 181.2~185.2 / 532.5~544.9 | 174,856 / 84.1~84.5 / 98.0~99.8 |
| 쏠림 (87,390개) | hit | 56 / 1.11~1.14 / 11.7~12.4 | 56 / 1.13~1.16 / 12.7~13.1 |

쏠림 스탬피드(측정 5묶음, 3회):

| 상태 | 동시 | 변경 전 실패 / p50 | 변경 후 실패 / p50 | 변경 후 순위 재계산 / 묶음 |
| --- | ---: | --- | --- | ---: |
| 캐시 없음 | 20 | 271 / 300 · 8,177~12,339ms | **0 / 300 · 973~988ms** | 20 |
| cold | 20 | 175 / 200 · 6,736~15,492ms | **0 / 300 · 966~1,009ms** | 20 |
| warm | 20 | 100 / 200 · 35ms | **0 / 300 · 28~35ms** | 0 |
| 캐시 없음 | 5 | 0 / 75 · 1,420~1,483ms | 0 / 75 · 206ms | 5 |

변경 후 앱 로그에 OutOfMemoryError가 없었다.

### 해석과 남은 것

- 원본 SELECT는 5개에서 6개가 됐다(정렬된 ID 페이지 + 그 20개 상품 조회). 그래도 앱이 87,390개 엔티티와 카운터 IN 조회를 만들지 않아, 쏠림 데이터의 원본 DB 시간이 약 절반, API가 약 1/5이 됐다. 앱 메모리는 페이지 크기만큼만 쓴다.
- DB는 여전히 브랜드의 활성 상품과 카운터를 모두 읽고 정렬한다(174,856행). 인기순 캐시가 덜어 주는 몫(hit 56행)은 그대로 크다.
- **만료 순간의 중복 계산은 남았다.** 쏠림 데이터의 cold 20에서는 모든 요청이 각자 순위를 계산했다(묶음당 20번). 계산 하나가 길어 첫 저장 전에 모든 요청이 원본에 들어갔기 때문이다. 실패는 없어졌지만 묶음의 DB 실행 시간 합은 약 17초였다(동시 실행으로 문장마다 CPU를 기다린 시간이 포함됨). 같은 키의 계산을 하나로 합치는 처리(single-flight)나 만료 전 갱신은 구현하지 않았다.
- 순차 단일 앱·한 노트북에서 앱·MySQL·Redis가 CPU를 나눠 쓴 결과다. 동시 80 이상과 지속 부하는 이 변경 뒤 다시 재지 않았다. 34절의 수치는 변경 전 코드의 기록으로 유지한다.

변경 후 쏠림 스탬피드(`measurements/100k-popular-cache-stampede-skew-after.json`), 변경 후 쏠림 요청당(`measurements/100k-popular-cache-db-time-skew-after.json`), 변경 후 정상 요청당(`measurements/100k-popular-cache-db-time-after.json`) — 모두 현재 로컬 보관.

<a id="cache-target-same-condition"></a>

## 37. 같은 조건에서 다시 잰 가격순·인기순 캐시 비교

[캐시 대상 기준](design.md#cache-target-criterion)과 [캐시가 덜어 주는 DB 일](cache-db-work.md)의 표를 만들 때 처음에는 다른 실험(가격순 쏠림 API 측정, 변경 전 JAR의 33절 값)을 섞었다. 조건을 맞추려고 가격순 두 경우를 36절과 같은 현재 JAR(SHA-256 앞 16자리 `86a54ef466f84927`)·같은 실행기로 다시 쟀다. 인기순 두 경우는 36절의 변경 후 값이 같은 JAR이다. 데이터(원본 10만 건, 쏠림은 ID 1~90,000을 브랜드 7로 변경)·요청·반복·지표 조건과 문장별 분해(공통 문장과 상품 선택 문장의 읽은 행·DB 시간)는 [캐시가 덜어 주는 DB 일](cache-db-work.md)에 정리했다. 문장별 값은 각 JSON의 `digests`를 3회 평균한 것이다.

| 경로 | 브랜드 7 | 원본 DB(ms) | hit DB(ms) | 원본 API(ms) | hit API(ms) | miss DB(ms) / 같은 공백의 원본 DB(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 가격순 | 990개 | 1.11~1.13 | 1.09~1.10 | 8.9 | 10.2~10.4 | 1.41~1.43 / 1.38~1.39 |
| 가격순 | 87,390개 | 1.32 | 1.11~1.13 | 9.0~9.1 | 10.1~10.2 | 1.65~1.66 / 1.61 |
| 인기순 | 990개 | 2.33~2.36 | 1.05~1.08 | 11.3 | 10.3~10.5 | 2.74~2.77 / 2.70~2.73 |
| 인기순 | 87,390개 | 84.2~84.5 | 1.13~1.16 | 98.0~99.8 | 12.7~13.1 | 85.1~86.3 / 85.2~90.5 |

가격순 990개의 값은 33절(변경 전 JAR, 원본 1.22~1.24ms·hit 감소 0~6%)과 회차 범위가 조금 다르지만 같은 결론이다. 33절 값은 당시 기록으로 유지한다. 원본 DB 스냅샷이 전후 같았고 임시 자원을 정리했다. 가격순 990개(`measurements/100k-price-cache-db-time-current.json`), 가격순 87,390개(`measurements/100k-price-cache-db-time-skew-current.json`) — 현재 로컬 보관.

<a id="popular-product-cache"></a>

## 38. 인기순: 목록 ID 캐시와 상품별 캐시 분리

[ADR 0027](../adr/0027-popular-product-cache.md)에 따라 인기순 목록 캐시(ID 20개)는 그대로 두고, 상품 이름·가격을 상품마다 저장하는 상품 캐시를 추가했다. 흐름 예시는 [인기순: 목록 캐시와 상품 캐시 나누기](popular-product-cache.md)에 둔다.

### 바꾼 것

- domain: `CachedProduct`(ID·브랜드 ID·이름·가격·생성 시각), `ProductCache`(getAll·putAll·evict).
- infrastructure: `RedisProductCache`. MGET으로 한 번에 읽고, 손상 값·키와 다른 ID·필수 값 누락은 없는 것으로 본다. Redis 오류는 빈 결과로 우회한다. 키 `prefix:product:{id}`, TTL 300초.
- application: `ProductChanged`를 `ProductFacade.update`·`delete`가 발행하고, `ProductCacheInvalidator`가 AFTER_COMMIT에 그 상품 키를 지운다(`commerce.cache.product.enabled=true`일 때만 등록).
- `ProductQueryService`: 인기순 캐시와 상품 캐시가 모두 켜진 읽기 트랜잭션이면 hit는 `findActiveIds(brandId, ids)`로 활성 확인 뒤 상품 캐시에서 읽고, 없는 상품만 `findActiveByIds`로 채운다. miss도 순위 ID를 얻은 뒤 같은 방식이다. 상품 캐시가 꺼져 있으면 기존 흐름 그대로다.
- 설정: local·benchmark에 `commerce.cache.product.enabled: true`, `ttl-seconds: 300`, prefix `commerce:product:v1:local`·`:benchmark`.

### 테스트

- 새 `PopularProductCacheIntegrationTest` 5건(실제 MySQL·Redis 컨테이너): 캐시 hit에서 `findActiveByIds` 미호출·`findActiveIds` 호출과 같은 응답, 수정 시 해당 상품 키만 삭제·목록 키 유지·다음 hit의 새 이름/가격, 목록 키와 상품 키 삭제를 모두 실패시켜도 삭제 상품 비노출, 손상 값의 DB 대체와 재저장, 좋아요 등록 뒤 순서·상품 값 재사용과 현재 좋아요 수.
- 기존 `PopularPageCacheIntegrationTest`는 상품 캐시 기본값(false)으로 수정 없이 통과했다. `ProductQueryServiceTest`는 생성자 인자만 늘었다.
- `:apps:commerce-api:check` 58 suites·342건, 실패·오류·skip 0, Checkstyle·ArchUnit 통과.

### 측정

33절 실행기에 `--product-cache`를 더해 같은 JAR(`af4b4432c6e09653`)로 상품 캐시만 끄고 켰다. benchmark 프로필이 상품 캐시를 기본으로 켜므로 실행기가 매번 명시적으로 설정한다. 조건은 [캐시가 덜어 주는 DB 일](cache-db-work.md)과 같다.

| 브랜드 7 | 경우 | 끔: SELECT / 읽은 행 / DB(ms) / API(ms) | 켬: SELECT / 읽은 행 / DB(ms) / API(ms) |
| ---: | --- | --- | --- |
| 990개 | 원본 | 6 / 2,056 / 2.37~2.55 / 11.4~12.1 | 6 / 2,056 / 2.35~2.50 / 11.4~12.0 |
| 990개 | hit | 5 / 56 / 1.04~1.24 / 10.3~11.1 | 5 / 56 / 1.13~1.18 / 10.8~11.0 |
| 990개 | miss | 6 / 2,056 / 2.77~2.93 / 13.8~14.0 | 5 / 2,036 / 2.55~2.70 / 12.9~13.4 |
| 87,390개 | 원본 | 6 / 174,856 / 75.6~76.4 / 90.5~91.1 | 6 / 174,856 / 75.4~76.5 / 90.7~91.1 |
| 87,390개 | hit | 5 / 56 / 1.11~1.16 / 12.8~13.2 | 5 / 56 / 1.21~1.26 / 13.3~13.8 |
| 87,390개 | miss | 6 / 174,856 / 77.1~77.7 / 90.8~92.4 | 5 / 174,836 / 76.7~77.1 / 89.1~90.2 |

hit에서 바뀐 문장은 상품 20개 전체 행 조회(0.22ms)에서 활성 ID 확인(0.27~0.32ms)이었다. EXPLAIN ANALYZE에서 ID 확인은 브랜드 인덱스 커버링 범위 조회로 약 0.05ms, 전체 행 조회는 PK 범위 조회로 약 0.11ms였다. `performance_schema`의 문장 실행 시간은 두 문장 모두 0.2~0.3ms였고 읽는 컬럼을 줄인 차이는 이 범위에서 드러나지 않았다. 문장 실행 시간과 EXPLAIN ANALYZE 노드 시간의 차이, 상품 캐시 MGET이 API 시간에서 차지한 몫은 나눠 재지 않았다. miss에서는 상품 조회 문장이 빠졌다. 원본(캐시 없음) 경로는 상품 캐시를 쓰지 않으므로 두 조건이 같아야 하며 실제로 같은 범위였다.

이번 측정의 원본 순위 계산 시간(87,390개 약 75ms)은 36·37절(약 84ms)보다 짧다. 다른 실행이며 같은 JAR끼리만 비교한다.

### 측정 중 문제와 처리

첫 실행에서 `docker compose exec redis-cli` 호출이 10초 안에 응답하지 않아 측정이 중단됐고, 정리 단계도 Redis 호출에서 멈춰 임시 DB·권한·`events_statements_cpu` 설정이 남았다. 직접 REVOKE·DROP·설정 원복·키 삭제를 했다. 실행기는 Redis 호출을 최대 3회 재시도하고, 정리 단계는 하나가 실패해도 나머지를 실행한 뒤 실패를 알리도록 고쳤다. 정리 때 이번 실행의 prefix로 만든 모든 키(상품 키 포함)를 지운다. 중단된 실행의 결과는 버리고 다시 쟀다.

끔 990개(`measurements/100k-popular-product-cache-off.json`), 켬 990개(`measurements/100k-popular-product-cache-on.json`), 끔 87,390개(`measurements/100k-popular-product-cache-off-skew.json`), 켬 87,390개(`measurements/100k-popular-product-cache-on-skew.json`) — 현재 로컬 보관.

<a id="popular-product-cache-reverted"></a>

## 39. 상품 캐시 되돌림

38절의 측정에서 인기순 hit의 DB 일은 줄지 않았다. 순위 계산은 목록 캐시가 이미 없애고, 상품 캐시가 없앨 수 있는 상품 조회 문장(PK 20행)은 삭제 확인 문장으로 다시 채워졌다. 공유로 이득을 낼 후보였던 가격순 목록은 운영 시 캐시 없이 조회하기로 했고, 상품 캐시를 붙이면 가격을 다시 캐시에 두게 된다. 그래서 상품 캐시를 되돌렸다([ADR 0027](../adr/0027-popular-product-cache.md)).

- 삭제: `CachedProduct`, `ProductCache`, `RedisProductCache`, `ProductChanged`, `ProductCacheInvalidator`, `PopularProductCacheIntegrationTest`.
- 원복: `ProductQueryService`의 인기순 경로와 생성자, `ProductFacade`의 이벤트 발행, `application.yml`의 product 캐시 설정, `ProductQueryServiceTest`의 생성자 인자. `application.yml`·`ProductFacade`는 커밋된 상태와 같아졌다. ADR 0026의 DB 정렬 경로는 유지했다.
- 문서: ADR 0027을 Rejected(구현·측정 뒤 되돌림)로 바꾸고, `popular-product-cache.md`·design.md·setup.md를 되돌린 상태에 맞췄다. 38절의 구현·측정 기록은 그대로 둔다.
- 측정 실행기의 `--product-cache` 옵션은 38절 재현 방법으로 남겼다. 현재 코드에는 해당 설정이 없어 효과가 없다.
- `:apps:commerce-api:check` 57 suites·337건, 실패·오류·skip 0, Checkstyle·ArchUnit 통과. JAR도 되돌린 코드로 다시 빌드했다.

<a id="review-fixes-ranking"></a>

## 40. 리뷰 반영: 같은 트랜잭션의 삭제와 측정 해석

### 같은 쓰기 트랜잭션에서 삭제 뒤 좋아요순 조회

36절의 순위 조회는 처음에 JDBC로 직접 실행했다. 같은 쓰기 트랜잭션에서 상품을 삭제한 뒤 좋아요순을 읽으면, JPA가 아직 DB에 반영하지 않은 삭제를 JDBC가 보지 못해 삭제된 상품을 순위에 넣었다. 이어지는 JPA 상품 조회는 자동 flush로 삭제를 반영해 그 상품을 뺐고, 페이지가 덜 찼다.

```text
상품 A·B, B에 좋아요 1개
같은 트랜잭션: B 삭제 → 좋아요순 1개 조회
기대 [A] / 수정 전 실제 [] / 수정 후 [A]
```

순위 SQL을 JPA native query로 바꿨다. Hibernate AUTO flush가 native query 전에 대기 중인 변경을 반영한다. SQL·정렬·필터는 같다. `ProductLikesOrderIntegrationTest`에 이 경우를 추가했고, 수정 전에는 실패·수정 후에는 통과했다. 일반 읽기 트랜잭션에는 대기 중인 변경이 없어 동작이 같다.

### 측정·문서 보완

- 38절과 ADR 0027의 해석에서 "문장 하나를 보내고 처리하는 고정 비용", "MGET 왕복만큼 느렸다"는 표현을 뺐다. 0.2~0.3ms는 `performance_schema`의 MySQL 문장 실행 시간이며 앱에서 잰 왕복 시간이 아니다. MGET이 API 시간에서 차지한 몫도 나눠 재지 않았다. "hit의 DB 작업이 줄지 않았고 API 지연 개선도 확인하지 못했다"까지만 쓴다. 상품 캐시를 되돌린 판단은 그대로다.
- 측정 실행기의 `--product-cache`는 상품 캐시 클래스가 없는 JAR에서 시작 전에 거절한다. 이전 측정 JSON은 그대로다.
- design.md 최신성 표의 "최대 TTL 300초"에 "옛 값이 저장된 시점부터"를 붙였다. 변경 commit부터의 상한이 아니다.
- 스탬피드 문서의 "hit → DB 거의 안 감"을 "순위 재계산을 건너뜀(상품·좋아요 수·하트 조회는 남음)"으로 고쳤다.

`:apps:commerce-api:check` 57 suites·338건, 실패·오류·skip 0, Checkstyle·ArchUnit 통과. JAR를 다시 빌드했다. 35~38절의 성능 수치는 JDBC로 실행하던 빌드의 기록이며, 같은 SQL이라 다시 재지 않았다.

<a id="popular-sustained"></a>

## 41. 인기순: TTL 만료를 포함한 지속 조회

지금까지는 hit·miss 하나, 또는 키를 지운 직후 한 묶음처럼 순간의 비용을 쟀다. 87,390개 브랜드 기준 hit 하나는 DB 1.13~1.16ms, 캐시 없음(원본) 하나는 84.2~84.5ms, miss 하나는 85.1~86.3ms였다(36절, 순차 요청). 실제로는 hit가 이어지다 TTL이 끝나는 순간 겹친 계산이 생긴다. hit로 아낀 것보다 만료 순간에 겹쳐 쓴 것이 크면 캐시를 둬도 전체로는 손해일 수 있다(예: TTL 1초라면 매초 20개 요청이 각자 원본을 계산해 캐시 없음과 비슷해진다). 그래서 **평소 hit로 아낀 이득이 만료 순간의 겹친 계산까지 포함해도 유지되는지** 확인했다.

### 측정 전 예측

- 87,390개 브랜드에서 Redis의 요청당 평균 DB 시간이 캐시 없음보다 수십 배 작을 것이다.
- 만료 순간의 겹친 계산 때문에 Redis의 p95·p99는 hit보다 크게 튈 것이다.

### 조건

- 실행기에 `--sustain`(지속 시간)·`--threads`(스레드 수)를 더했다. 스레드 20개가 150초 동안 쉬지 않고 같은 인기순 요청을 보낸다. 측정 시작 전 20회 요청으로 앱을 데우고 키를 채운다. Redis 키는 지우지 않아 benchmark 기본 TTL 60초로 저절로 만료된다(150초 동안 2번).
- 같은 JAR(`01681b0ada2cf5ca`, 40절 수정 반영)로 캐시 없음/Redis를 같은 강도로 앱 순서를 바꿔 3회 쟀다. 데이터·요청 조건은 [캐시가 덜어 주는 DB 일](cache-db-work.md)과 같다. 요청당 DB 시간은 측정 구간의 `performance_schema` 문장 실행 시간 합 ÷ 요청 수다. 요청마다 시작 시점·응답 시간·실패를 보관했다.
- 정해 둔 요청 패턴의 결과이며 운영 히트율이 아니다.

### 결과 (3회 범위)

| 데이터 | 경우 | 처리량(요청/초) | 실패 | p50 / p95 / p99(ms) | 요청당 DB 실행 시간 | 150초 동안 순위 재계산 |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| 87,390개 | 캐시 없음 | 21.0 | 0 | 971~974 / 1,298~1,320 / 1,424~1,439 | 884~886ms | 3,153~3,165 (모든 요청) |
| 87,390개 | Redis | 1,147~1,154 | 0 | 16.0 / 24.4~24.6 / 30.8~32.2 | 1.50~1.52ms | 40 |
| 990개 | 캐시 없음 | 921~938 | 0 | 19.3~20.7 / 29.7~33.0 / 37.0~48.6 | 3.17~3.59ms | 138,150~140,597 (모든 요청) |
| 990개 | Redis | 1,160~1,182 | 0 | 15.5~15.9 / 24.0~26.6 / 31.6~43.1 | 1.18~1.30ms | 20~32 |

- 87,390개 브랜드: 만료 비용까지 포함해도 요청당 DB 시간이 약 1/590(884ms → 1.5ms), 처리량이 약 55배였다. 150초 동안 재계산 40번은 두 번의 만료마다 스레드 20개가 각자 계산한 것이다.
- 가장 느린 40개 요청은 세 회차 모두 측정 시작 후 59초·120초 지점, 곧 TTL이 끝난 두 순간에 몰려 있었다. 응답은 509~1,054ms였다. 전체 요청 약 17만 3천 개 중 40개라 p95·p99에는 드러나지 않았다.
- 990개 브랜드: 재계산이 짧아(요청당 약 2~3ms) 만료 순간이 응답 기록에서 구분되지 않았다. 요청당 DB 시간은 약 1/2.8, 처리량은 약 1.27배였다.
- 동시 요청에서는 요청당 DB 시간이 순차 측정(990개 원본 약 2.35ms)보다 커졌다. 문장들이 CPU를 나눠 쓴 시간이 포함되기 때문이다.

### 예측과 비교

- 첫 번째 예측(수십 배)은 맞았고, 차이는 예상보다 컸다.
- 두 번째 예측(p95·p99가 튐)은 빗나갔다. 만료 순간의 느린 응답은 실재했지만(약 0.5~1초), 60초에 한 번 20개 요청에만 생겨 백분위수에 드러나지 않았다. 만료 순간 지연을 보려면 백분위수가 아니라 최댓값이나 시간대별 기록을 봐야 한다.

### 결론

인기순 캐시의 이득은 만료 순간의 겹친 계산까지 포함해도 유지됐다. 남은 비용은 60초마다 그 순간 들어온 요청 몇 개가 약 1초를 기다리는 것이다. 허용 응답 시간을 정하지 않았으므로 이 지연을 막을지는 [캐시 스탬피드](cache-stampede.md)의 다시 볼 조건에 따른다.

87,390개(`measurements/100k-popular-sustained-skew.json`), 990개(`measurements/100k-popular-sustained.json`) — 요청별 기록 포함, 현재 로컬 보관. 원본 DB 스냅샷이 전후 같았고 임시 자원을 정리했다.

```bash
DOCKER_CONTEXT=desktop-linux caffeinate -i python3 scripts/measure-week4-price-cache-db-time.py \
  --sort popular --brand-skew --sustain 150 --threads 20 \
  --jar apps/commerce-api/build/libs/commerce-api-<hash>.jar --output docs/week4/measurements/<new-file>.json
```
