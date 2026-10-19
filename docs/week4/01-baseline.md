# 4주차 01. 상품 목록 조회 개선 전 기준선

측정값만 기록한다. 해석과 개선안은 다른 문서에서 다룬다. 원문은 `docs/week4/perf/baseline/` 에, 도구는 `docs/week4/perf/scripts/` 에 있다.

## 1. 환경 (`perf/baseline/env.txt`)

| 항목 | 값 |
|---|---|
| 측정 시각 | 2026-10-16 11:43 ~ 11:58 KST |
| git HEAD / 브랜치 | `4e1a87d` / applejeong/volume-4 |
| git status | `docs/week4/` 미추적만 있음. 추적 파일 변경 없음 |
| 앱 기동 | `./gradlew :apps:commerce-api:bootRun --console=plain` (local 프로필, 실행 옵션 없음, Hibernate Statistics 꺼짐) |
| MySQL | 8.0.46 (docker `infra-compose.yml` mysql 서비스) |
| 데이터 | brands 100, users 1000, products 10000, likes 223,037 (`apps/commerce-api/src/test/resources/sql/` 의 seed·verify, verify 20개 항목 전부 OK) |
| 인덱스 | products: PRIMARY(id) / likes: PRIMARY(id), uk_likes_user_product(user_id, product_id) / brands: PRIMARY(id) |
| k6 | v2.3.0 (darwin/arm64) |
| Java / OS | OpenJDK 23.0.2 / Darwin 25.6.0 arm64 |

## 2. 측정 조건 (개선 후에도 동일하게 재현한다)

- 순서: 재기동 → seed → verify → 1 환경 기록 → 2 A 1회 SQL 캡처 → 3 A 응답 저장 → 4 A EXPLAIN → 5 A k6 VU 1 → 6 A k6 VU 5 → 7 B → 8 C. 측정 시작 전에는 어떤 API 도 호출하지 않는다.
    - seed: `docker compose -f ./docker/infra-compose.yml exec -T mysql mysql -uapplication -papplication loopers < apps/commerce-api/src/test/resources/sql/product-perf-seed.sql`
    - verify: 같은 명령에 `--table` 을 붙이고 `product-perf-verify.sql` 을 넣는다. 20개 항목이 전부 OK 여야 한다.
- 대상
    - A: `GET /api/v1/products?brandId=7&sort=price_asc&page=0&size=20` (주)
    - B: `GET /api/v1/products?brandId=7&sort=likes_desc&page=0&size=20` (보조)
    - C: `GET /api/v1/products?sort=price_asc&page=0&size=20` (브랜드 필터 없음, SQL 개수만)
- SQL 개수·내용: MySQL performance_schema 만 사용. 앱 설정은 바꾸지 않는다.
    - 측정용 mysql 클라이언트는 `root` 로 접속하고, 앱은 `application` 으로 접속하므로 `threads.PROCESSLIST_USER = 'application'` 으로 앱 문장만 고른다 (`_client_name` 도 앱은 `MySQL Connector/J`, 클라이언트는 `libmysql` 로 구분됨).
    - consumer `events_statements_history_long` 을 `setup_consumers` 에서 YES 로 켠다 (`perf/scripts/ps-setup.sql`). MySQL 8 기본값은 NO 이고 컨테이너 재시작 시 돌아가므로 **개선 후 측정 때도 같은 설정으로 켜고 잰다**.
    - 1회 호출: `ps-reset.sql` 로 초기화 → 호출 → `ps-capture.sql` 로 문장 목록(`SQL_TEXT`, 파라미터 채워진 형태)과 계정별 종류 카운터 기록.
    - k6 구간: `ps-round-capture.sql` 로 digest 기준 "상품 조회 SQL 3종 합계"(상품 목록 / 좋아요 집계 / 브랜드 조회)와 "그 외 SELECT"(application SELECT 총수 − 3종)를 나눠 기록. 요청당 SQL 수 = 3종 합계 ÷ 요청 수.
    - 부수 문장(`SET SESSION TRANSACTION READ ONLY/READ WRITE`, `SET autocommit`, `COMMIT`, `com/Ping`)은 JDBC·HikariCP 가 내는 것으로 SQL 개수에 넣지 않고 따로 적는다.
- 응답 시간: k6 `scripts/k6-products.js` (shared-iterations, 200 과 20건 check). `scripts/k6-run.sh <VUS> <라벨> <단계 폴더>` 가 회차마다 준비 50회(결과 제외) → 카운터 초기화 → 측정 500회 → SQL 개수 캡처를 3회 반복하고, 측정 1번(VU별)당 결과 2개를 `perf/<단계 폴더>/` 에 남긴다 (같은 파일이 있으면 덮어쓰지 않고 멈춤). 이번 측정은 `k6-run.sh 1 vu1 baseline`, `k6-run.sh 5 vu5 baseline` 으로 실행했다. req/s 는 k6 `http_reqs.rate`.
    - `k6-A-<라벨>-summary.tsv`: 회차 1·2·3 행 + mean / min / max / range_pct 행. 열은 vus, iters, reqs, avg/p50/p90/p95/max(ms), req_s, http_fail, check_fail, 상품 조회 SQL 3종(sql_list, sql_like_agg, sql_brand)과 합계, other_select, sql_per_req.
    - `k6-A-<라벨>-run.log`: 전체 실행 로그 (회차별 k6 한 줄 요약과 SQL 캡처 출력 포함). 회차별 중간 파일은 남기지 않는다.
    - 요약 수치는 summary.tsv(스크립트 printf 반올림) 기준이다. mean 행의 백분위(p50·p90·p95)와 max 는 회차별 값의 평균이며, 전체 요청을 합친 백분위가 아니다.
- 단계 폴더 규칙: 개선 전은 `baseline`, 개선 후는 적용한 순서대로 `step1`, `step2`, … 폴더에 같은 절차로 측정한다. 단계 폴더 안의 파일 이름은 접두어 없이 동일하다.
- B 는 10회 순차 curl 로 재고, 1회가 60초를 넘으면 중단한다.
- EXPLAIN / EXPLAIN ANALYZE 는 캡처한 실제 SQL 을 root 로 실행한다 (`baseline/A-explain.sql`, `baseline/B-explain.sql`).

## 3. 단계별 결과

### 3-1. A 1회 호출 SQL (`perf/baseline/A-sql.txt`)

응답 200, 0.178초 (재기동 직후 첫 요청, 측정값 아님). SELECT 3개, 모두 한 커넥션.

| 순서 | SQL | ms | rows_examined | no_index_used |
|---|---|---|---|---|
| 1 | 상품 목록: `select ... from products p1_0 where deleted_at is null and brand_id=7 order by amount, id desc limit 0,20` | 1.983 | 10,020 | 1 |
| 2 | 좋아요 집계: `select product_id, count(id) from likes where product_id in (20건) group by product_id` | 13.428 | 223,037 | 0 |
| 3 | 브랜드 조회: `select ... from brands where id=7` | 0.084 | 1 | 0 |

부수 문장: set_option 12, commit 3, Ping 1 (트랜잭션 3개: 상품 조회·좋아요 집계·브랜드 조회 각각).

### 3-2. A 응답 (`perf/baseline/A-response.json`)

20건, 모두 brand-7, 재고 100. 1차·2차 시도의 응답과 바이트 단위로 동일.

| 순서 | productId | price | likeCount | 순서 | productId | price | likeCount |
|---|---|---|---|---|---|---|---|
| 1 | 5406 | 122 | 14 | 11 | 206 | 7722 | 47 |
| 2 | 2706 | 222 | 0 | 12 | 8406 | 11122 | 4 |
| 3 | 6 | 322 | 32 | 13 | 5706 | 11222 | 13 |
| 4 | 8206 | 3722 | 0 | 14 | 3006 | 11322 | 22 |
| 5 | 5506 | 3822 | 45 | 15 | 306 | 11422 | 31 |
| 6 | 2806 | 3922 | 7 | 16 | 8506 | 14822 | 35 |
| 7 | 106 | 4022 | 16 | 17 | 5806 | 14922 | 44 |
| 8 | 8306 | 7422 | 20 | 18 | 3106 | 15022 | 6 |
| 9 | 5606 | 7522 | 29 | 19 | 406 | 15122 | 15 |
| 10 | 2906 | 7622 | 38 | 20 | 8606 | 18522 | 19 |

### 3-3. A EXPLAIN / EXPLAIN ANALYZE (`perf/baseline/A-explain.txt`)

| SQL | type | key | rows (추정) | Extra |
|---|---|---|---|---|
| 1. 상품 목록 | ALL | NULL | 9955 | Using where; Using filesort |
| 2. 좋아요 집계 | index | uk_likes_user_product | 222504 | Using where; Using index; Using temporary |
| 3. 브랜드 조회 | const | PRIMARY | 1 | NULL |

| SQL | 실행 트리 노드 | actual rows | loops | actual time (ms) |
|---|---|---|---|---|
| 1 | Table scan on p1_0 | 10000 | 1 | 0.0823..1.47 |
| 1 | Filter (brand_id = 7 and deleted_at is null) | 100 | 1 | 0.0855..1.71 |
| 1 | Sort amount, id DESC (limit 20) | 20 | 1 | 1.76..1.76 |
| 2 | Covering index scan on uk_likes_user_product | 223037 | 1 | 0.507..14.9 |
| 2 | Filter (product_id in 20건) | 437 | 1 | 0.516..20 |
| 2 | Aggregate using temporary table | 18 | 1 | 20.3..20.3 |
| 3 | Rows fetched before execution (const) | 1 | 1 | 0.000042 |

### 3-4. A 응답 시간, VU 1명 (`perf/baseline/k6-A-vu1-summary.tsv`, `k6-A-vu1-run.log`)

회차마다 준비 50 + 측정 500. 단위 ms.

| 회차 | avg | p50 | p90 | p95 | max | req/s | HTTP 실패 | check 실패 | 상품 조회 SQL 3종 | 그 외 SELECT | SQL ÷ 500 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 22.09 | 21.98 | 23.05 | 23.37 | 37.67 | 44.90 | 0 | 0 | 1500 (500/500/500) | 0 | 3.000 |
| 2 | 22.09 | 22.04 | 23.05 | 23.33 | 27.87 | 44.89 | 0 | 0 | 1500 (500/500/500) | 0 | 3.000 |
| 3 | 22.00 | 22.00 | 22.92 | 23.24 | 28.54 | 45.11 | 0 | 0 | 1500 (500/500/500) | 0 | 3.000 |

회차 간 편차 (범위 ÷ 평균): avg 0.4%, p50 0.3%, p90 0.6%, p95 0.6%, max 31.2%, req/s 0.5%. 부수 문장 회차당 commit 1500, set_option 6000.

### 3-5. A 응답 시간, VU 5명 (`perf/baseline/k6-A-vu5-summary.tsv`, `k6-A-vu5-run.log`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | HTTP 실패 | check 실패 | 상품 조회 SQL 3종 | 그 외 SELECT | SQL ÷ 500 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 25.14 | 24.90 | 27.99 | 29.55 | 38.38 | 197.10 | 0 | 0 | 1500 (500/500/500) | 0 | 3.000 |
| 2 | 25.29 | 24.82 | 28.08 | 30.02 | 45.92 | 196.56 | 0 | 0 | 1500 (500/500/500) | 0 | 3.000 |
| 3 | 25.91 | 25.06 | 28.79 | 31.16 | 61.01 | 191.83 | 0 | 0 | 1500 (500/500/500) | 0 | 3.000 |

회차 간 편차 (범위 ÷ 평균): avg 3.0%, p50 1.0%, p90 2.8%, p95 5.3%, max 46.7%, req/s 2.7%.

VU 1명 vs 5명 (3회차 평균)

| 지표 | VU 1명 | VU 5명 | 차이 | 비율 |
|---|---|---|---|---|
| avg (ms) | 22.06 | 25.45 | +3.39 | 1.15 |
| p50 (ms) | 22.01 | 24.93 | +2.92 | 1.13 |
| p90 (ms) | 23.01 | 28.29 | +5.28 | 1.23 |
| p95 (ms) | 23.31 | 30.24 | +6.93 | 1.30 |
| max (ms) | 31.36 | 48.44 | +17.08 | 1.54 |
| req/s | 44.97 | 195.16 | +150.19 | 4.34 |
| 요청당 SQL | 3.000 | 3.000 | 0 | 1.00 |

### 3-6. B (likes_desc, brandId=7) (`perf/baseline/B-sql.txt`, `B-response.json`, `B-explain.txt`, `B-10calls.txt`, `B-10calls-sql.txt`)

1회 호출: 응답 200, 1.381초. SELECT 3개.

| 순서 | SQL | ms | rows_sent | rows_examined | no_index_used |
|---|---|---|---|---|---|
| 1 | 상품 목록: `... where deleted_at is null and brand_id=7 order by (select count(l1_0.id) from likes l1_0 where l1_0.product_id=p1_0.id) desc, id desc limit 0,20` | 1294.861 | 20 | 22,313,720 | 1 |
| 2 | 좋아요 집계: `... where product_id in (20건) group by product_id` | 13.564 | 20 | 223,037 | 0 |
| 3 | 브랜드 조회: `... where id=7` | 0.112 | 1 | 1 | 0 |

응답 20건 (productId / likeCount): 9606/47, 206/47, 9906/46, 5206/46, 5506/45, 806/45, 5806/44, 1106/44, 6106/43, 1406/43, 6406/42, 1706/42, 6706/41, 2006/41, 7006/40, 2306/40, 7306/39, 2606/39, 7606/38, 2906/38. 모두 brand-7.

EXPLAIN

| SQL | select_type | table | type | key | rows (추정) | Extra |
|---|---|---|---|---|---|---|
| 1 외부 | PRIMARY | p1_0 | ALL | NULL | 9955 | Using where; Using filesort |
| 1 정렬 서브쿼리 | DEPENDENT SUBQUERY | l1_0 | index | uk_likes_user_product | 222504 | Using where; Using index |
| 2 | SIMPLE | l1_0 | index | uk_likes_user_product | 222504 | Using where; Using index; Using temporary |
| 3 | SIMPLE | b1_0 | const | PRIMARY | 1 | NULL |

EXPLAIN ANALYZE

| SQL | 실행 트리 노드 | actual rows | loops | actual time (ms) |
|---|---|---|---|---|
| 1 | Table scan on p1_0 | 10000 | 1 | 0.131..1.48 |
| 1 | Filter (brand_id = 7 and deleted_at is null) | 100 | 1 | 0.134..1.77 |
| 1 | Sort (select #2) DESC, id DESC (limit 20) | 20 | 1 | 1712..1712 |
| 1 서브쿼리 | Covering index scan on uk_likes_user_product | 223037 | 100 | 0.119..12.5 |
| 1 서브쿼리 | Filter (product_id = p1_0.id) | 22.1 | 100 | 1.9..17.1 |
| 1 서브쿼리 | Aggregate count(l1_0.id) | 1 | 100 | 17.1..17.1 |
| 2 | Covering index scan on uk_likes_user_product | 223037 | 1 | 0.135..12.3 |
| 2 | Filter (product_id in 20건) | 850 | 1 | 0.145..17.3 |
| 2 | Aggregate using temporary table | 20 | 1 | 17.5..17.5 |
| 3 | Rows fetched before execution (const) | 1 | 1 | 0.000042 |

10회 순차 호출 (초): 1.136, 1.175, 1.135, 1.184, 1.310, 1.129, 1.177, 1.128, 1.175, 1.308. 모두 200.

| avg | p50 | min | max | 상품 조회 SQL 3종 | 그 외 SELECT | SQL ÷ 10 |
|---|---|---|---|---|---|---|
| 1.186 | 1.175 | 1.128 | 1.310 | 30 (10/10/10) | 0 | 3.000 |

### 3-7. C (price_asc, 브랜드 필터 없음) (`perf/baseline/C-sql.txt`, `C-response.json`)

1회 호출: 응답 200, 0.084초. **SELECT 15개** = 상품 목록 1 + 좋아요 집계 1 + 브랜드 조회 13. 응답 20건의 brandId 는 4, 7, 10, 2, 5, 8, 11, 3, 6, 9, 12, 4, 7, 10, 13, 5, 8, 11, 14, 6 으로 서로 다른 브랜드 13개이며, 브랜드 조회가 `where id=2` ~ `where id=14` 로 브랜드마다 1회씩 실행됐다.

| 종류 | ms | rows_examined |
|---|---|---|
| 상품 목록 (`where deleted_at is null order by amount, id desc limit 0,20`) | 2.424 | 10,020 |
| 좋아요 집계 (IN 20건) | 20.324 | 223,037 |
| 브랜드 조회 × 13 | 0.025 ~ 0.546 (합 1.369) | 각 1 |

부수 문장: set_option 60, commit 15, Ping 1 (트랜잭션 15개).

## 4. 측정 중 특이사항

측정 중 알게 된 사실
- 재기동 직후 첫 요청은 측정값보다 느리다 (예: A 첫 요청 0.4초, 측정 avg 22ms). 회차마다 준비 50회를 실행하고 결과에서 버리는 이유다.
- k6 중 HikariCP 가 유휴 커넥션 수(minimum-idle 30)를 채우려고 새 커넥션을 열면, Connector/J 의 연결 초기화 SELECT 2개(`@@session.auto_increment_increment ...`, `@@session.transaction_read_only`)가 application 계정의 SELECT 수에 섞인다. digest 기준으로 상품 조회 SQL 3종만 세고 나머지를 "그 외 SELECT"로 분리하는 이유다.

기타
- 2단계 A 1회 호출(0.178초)과 B 1회 호출(1.381초)은 각각 해당 경로의 첫 요청이라 참고값이다. B 응답 시간은 10회 반복값을 쓴다.
- 브랜드 미지정 likes_desc 는 측정 대상에 넣지 않았다. 설계 문서 7-2 에 적힌 117초(seed 직후 1회, size 3)는 정식 측정이 아니다.
- `events_statements_history_long` consumer 는 측정 전 시도에서 켠 뒤 컨테이너를 재시작하지 않아 YES 로 유지된 상태였다 (setup 단계에서 enabled_before YES 확인).

## 5. 원문 파일 (`docs/week4/perf/`)

`scripts/` 는 개선 전후 공용 도구, `baseline/` 은 개선 전 결과다. 개선 후 결과는 `step1/`, `step2/`, … 에 같은 파일 이름으로 둔다.

| 폴더 | 파일 | 내용 |
|---|---|---|
| `scripts/` | `ps-setup.sql`, `ps-reset.sql`, `ps-capture.sql`, `ps-round-capture.sql` | performance_schema 설정·초기화·캡처 SQL |
| `scripts/` | `k6-products.js`, `k6-run.sh` | k6 스크립트, 회차 실행 스크립트 (단계 폴더를 인자로 받고 summary.tsv · run.log 를 만듦) |
| `baseline/` | `env.txt` | 환경 기록 |
| `baseline/` | `A-sql.txt`, `A-response.json`, `A-explain.sql`, `A-explain.txt` | A 1회 호출 SQL, 응답, EXPLAIN |
| `baseline/` | `k6-A-vu1-summary.tsv`, `k6-A-vu1-run.log` | VU 1명 회차별 지표·SQL 개수 표, 실행 로그 |
| `baseline/` | `k6-A-vu5-summary.tsv`, `k6-A-vu5-run.log` | VU 5명 동일 |
| `baseline/` | `B-sql.txt`, `B-response.json`, `B-explain.sql`, `B-explain.txt`, `B-10calls.txt`, `B-10calls-sql.txt` | B |
| `baseline/` | `C-sql.txt`, `C-response.json` | C |

## 6. 개선 전 테스트 상태

2026-10-16 12:46 KST, HEAD `ad2a597`, 코드 변경 없음. `./gradlew :apps:commerce-api:test --tests '*Product*'` 실행 결과 BUILD SUCCESSFUL.

| 테스트 클래스 | 실행 | 실패 | 오류 | skip |
|---|---|---|---|---|
| ProductRepositoryTest (동률 시 id 역순 `appliesSecondarySort_whenPrimarySortIsTied` 포함) | 12 | 0 | 0 | 0 |
| ProductV1ApiE2ETest (GET /api/v1/products 4, 상세 2, 관리자 재고 3) | 9 | 0 | 0 | 0 |
| ProductFacadeTest | 11 | 0 | 0 | 0 |
| ProductServiceTest | 22 | 0 | 0 | 0 |
| ProductSortTypeTest | 3 | 0 | 0 | 0 |
| ProductTest | 12 | 0 | 0 | 0 |
| AdminCrudV1ApiE2ETest (상품 등록·수정·삭제 중첩 클래스) | 7 | 0 | 0 | 0 |
| LikeServiceTest (상품 좋아요 수 집계 중첩 클래스) | 1 | 0 | 0 | 0 |
| 합계 | 77 | 0 | 0 | 0 |

- 테스트 DB 는 Testcontainers MySQL 8.0 이다. `modules/jpa` testFixtures 의 `MySqlTestContainersConfig` 가 컨테이너를 띄우고 `datasource.mysql-jpa.main.jdbc-url` 을 매핑 포트(이번 실행은 `localhost:52705`)로 덮어쓴다. 측정용 로컬 MySQL(`docker-mysql-1`, 3306)과 별개이며, 테스트 전후 로컬 seed 건수(products 10000, likes 223,037, brands 100, users 1000)가 그대로였다.
