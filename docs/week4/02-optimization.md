# 4주차 02. 상품 목록 조회 개선 기록

측정값만 기록한다. 측정 조건·도구·순서는 `01-baseline.md` 2절과 같고, 단계별 원문은 `docs/week4/perf/<단계 폴더>/` 에 있다 (k6 결과는 `k6-A-<라벨>-summary.tsv` 와 `k6-A-<라벨>-run.log`). 요약 수치는 summary.tsv(스크립트 printf 반올림) 기준이며, mean 행의 백분위(p50·p90·p95)와 max 는 회차별 값의 평균이고 전체 요청을 합친 백분위가 아니다.

측정 조건 보충
- step2 VU5·B·C 는 스크립트 커밋 `e9b9f19` 이후 측정, 앱 코드는 `0f4a094` (스크립트 변경은 결과 파일 형식만).
- 요청당 SQL 수는 계정(application) 기준 SELECT 합계 ÷ 요청 수로 적는다. digest 기준 3종 합계는 보조 값이다.
- A·B·C 1회 호출의 ms 는 표본 1개라 "참고값(1회)" 이다. 단계 간 비교는 rows_examined·실행 계획·SQL 수 중심으로 적는다.
- C EXPLAIN 은 step2·step3 에서만 기록했다 (baseline·step1 없음). step2 → step3 비교만 가능하다.

## step1: likes(product_id) 인덱스

### 변경

- 커밋 `43c07d5` feat: likes(product_id) 인덱스 추가 (step1)
- `Like` 엔티티 `@Table` 에 `@Index(name = "idx_likes_product_id", columnList = "product_id")` 1개 추가. 기존 `uk_likes_user_product(user_id, product_id)` 유지. 다른 코드 변경 없음.
- local·test 프로필은 `ddl-auto: create` 라 재기동·테스트 시 `create index idx_likes_product_id on likes (product_id)` 가 자동 실행된다. 다른 프로필은 별도 DDL 이 필요하다.
- 테스트: `./gradlew :apps:commerce-api:test --tests '*Like*' --tests '*Product*'` 109건 통과 (실패·오류·skip 0).

### 측정 환경 (`perf/step1/env.txt`)

2026-10-16 13:24 ~ 13:34 KST, HEAD `43c07d5`, 앱 옵션 없이 local 재기동, seed 후 verify 20개 OK. `SHOW INDEX FROM likes` 에 `idx_likes_product_id` (cardinality 9,222) 확인. 그 외 환경은 baseline 과 동일.

### 응답 일치

| 요청 | baseline 대비 응답 JSON | 캡처 SQL 문자열 |
|---|---|---|
| A (brandId=7, price_asc) | 바이트 단위 동일 | 3개 동일 |
| B (brandId=7, likes_desc) | 바이트 단위 동일 | 3개 동일 |
| C (필터 없음, price_asc) | 바이트 단위 동일 | 15개 동일 |

### SQL 수와 읽은 행 수 (1회 호출, performance_schema)

| 요청 | SQL | baseline SQL 수 | step1 SQL 수 | baseline ms / rows_examined | step1 ms / rows_examined |
|---|---|---|---|---|---|
| A | 상품 목록 | 1 | 1 | 1.983 / 10,020 | 2.536 / 10,020 |
| A | 좋아요 집계 (IN 20) | 1 | 1 | 13.428 / 223,037 | 0.290 / 437 |
| A | 브랜드 조회 | 1 | 1 | 0.084 / 1 | 0.126 / 1 |
| B | 상품 목록 (likes_desc 정렬 서브쿼리) | 1 | 1 | 1294.861 / 22,313,720 | 8.568 / 12,228 |
| B | 좋아요 집계 (IN 20) | 1 | 1 | 13.564 / 223,037 | 0.303 / 850 |
| B | 브랜드 조회 | 1 | 1 | 0.112 / 1 | 0.108 / 1 |
| C | 상품 목록 | 1 | 1 | 2.424 / 10,020 | 2.722 / 10,020 |
| C | 좋아요 집계 (IN 20) | 1 | 1 | 20.324 / 223,037 | 0.399 / 448 |
| C | 브랜드 조회 × 13 | 13 | 13 | 합 1.369 / 각 1 | 합 0.578 / 각 1 |

요청당 SQL 수는 A 3, B 3, C 15 로 변화 없음. 부수 문장(set_option, commit, Ping) 수도 동일.

### EXPLAIN 변화

| 요청 | SQL | 항목 | baseline | step1 |
|---|---|---|---|---|
| A | 상품 목록 | type / key / rows / Extra | ALL / NULL / 9955 / Using where; Using filesort | ALL / NULL / 9855 / 동일 |
| A | 좋아요 집계 | type / key / rows / Extra | index / uk_likes_user_product / 222504 / Using where; Using index; Using temporary | range / idx_likes_product_id / 439 / Using where; Using index |
| A | 브랜드 조회 | type / key | const / PRIMARY | const / PRIMARY |
| B | 상품 목록 외부 | type / key / rows / Extra | ALL / NULL / 9955 / Using where; Using filesort | ALL / NULL / 9855 / 동일 |
| B | 정렬 서브쿼리 (DEPENDENT SUBQUERY) | type / key / rows / Extra | index / uk_likes_user_product / 222504 / Using where; Using index | ref / idx_likes_product_id / 24 / Using index |
| B | 좋아요 집계 | type / key / rows / Extra | index / uk_likes_user_product / 222504 / Using where; Using index; Using temporary | range / idx_likes_product_id / 850 / Using where; Using index |

EXPLAIN ANALYZE 주요 노드 (actual rows / loops / actual time ms)

| 요청 | 노드 | baseline | step1 |
|---|---|---|---|
| A | 좋아요 집계: likes 접근 | Covering index scan 223037 / 1 / 0.507..14.9 | Covering index range scan 437 / 1 / 0.00733..0.0575 |
| A | 좋아요 집계: 집계 | Aggregate using temporary table 18 / 1 / 20.3 | Group aggregate 18 / 1 / 0.0154..0.0909 |
| A | 상품 목록: Sort amount, id DESC (limit 20) | 20 / 1 / 1.76 | 20 / 1 / 2.32 |
| B | 정렬 서브쿼리: likes 접근 | Covering index scan 223037 / 100 / 0.119..12.5 | Covering index lookup 22.1 / 100 / 0.0106..0.012 |
| B | 정렬 서브쿼리: Aggregate count | 1 / 100 / 17.1 | 1 / 100 / 0.013 |
| B | 상품 목록: Sort (select #2) DESC, id DESC (limit 20) | 20 / 1 / 1712 | 20 / 1 / 3.61 |
| B | 좋아요 집계: likes 접근 | Covering index scan 223037 / 1 / 0.135..12.3 | Covering index range scan 850 / 1 / 0.011..0.0785 |
| B | 좋아요 집계: 집계 | Aggregate using temporary table 20 / 1 / 17.5 | Group aggregate 20 / 1 / 0.0225..0.133 |

### A 응답 시간 (k6, 준비 50 + 측정 500 × 3회, 단위 ms)

VU 1명 회차별 (`perf/step1/k6-A-vu1-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 상품 조회 SQL 3종 | 그 외 SELECT |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 6.94 | 6.66 | 7.80 | 8.29 | 26.72 | 142.71 | 0 | 1500 | 0 |
| 2 | 6.65 | 6.27 | 7.22 | 8.00 | 17.45 | 148.75 | 0 | 1500 | 0 |
| 3 | 6.83 | 6.61 | 8.15 | 8.50 | 12.52 | 144.34 | 0 | 1500 | 0 |

회차 간 편차 (범위 ÷ 평균): avg 4.3%, p50 6.0%, p90 12.0%, p95 6.1%, max 75.1%, req/s 4.2%.

VU 5명 회차별 (`perf/step1/k6-A-vu5-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 상품 조회 SQL 3종 | 그 외 SELECT |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 8.56 | 8.15 | 9.89 | 10.71 | 20.25 | 577.50 | 0 | 1500 | 0 |
| 2 | 8.74 | 8.36 | 10.21 | 10.85 | 21.90 | 566.14 | 0 | 1500 | 0 |
| 3 | 8.25 | 7.99 | 9.39 | 10.07 | 12.09 | 600.80 | 0 | 1500 | 0 |

회차 간 편차 (범위 ÷ 평균): avg 5.8%, p50 4.5%, p90 8.3%, p95 7.4%, max 54.3%, req/s 6.0%.

baseline 대비 (3회차 평균)

| 동시 요청 | 지표 | baseline | step1 | 차이 | 비율 (step1 ÷ baseline) |
|---|---|---|---|---|---|
| VU 1 | avg | 22.06 | 6.81 | −15.25 | 0.31 |
| VU 1 | p50 | 22.01 | 6.51 | −15.50 | 0.30 |
| VU 1 | p95 | 23.31 | 8.26 | −15.05 | 0.35 |
| VU 1 | max | 31.36 | 18.90 | −12.46 | 0.60 |
| VU 1 | req/s | 44.97 | 145.27 | +100.30 | 3.23 |
| VU 5 | avg | 25.45 | 8.52 | −16.93 | 0.33 |
| VU 5 | p50 | 24.93 | 8.17 | −16.76 | 0.33 |
| VU 5 | p95 | 30.24 | 10.54 | −19.70 | 0.35 |
| VU 5 | max | 48.44 | 18.08 | −30.36 | 0.37 |
| VU 5 | req/s | 195.16 | 581.48 | +386.32 | 2.98 |
| 공통 | 요청당 SQL | 3.000 | 3.000 | 0 | 1.00 |

### B 응답 시간 (10회 순차 curl, 단위 초)

step1 10회: 0.0218, 0.0096, 0.0082, 0.0118, 0.0086, 0.0103, 0.0084, 0.0132, 0.0081, 0.0084 (모두 200). 10회 구간 상품 조회 SQL 30 (10/10/10), 그 외 SELECT 0.

| 지표 | baseline | step1 | 비율 |
|---|---|---|---|
| avg | 1.186 | 0.0108 | 0.009 |
| p50 | 1.175 | 0.0091 | 0.008 |
| min | 1.128 | 0.0081 | 0.007 |
| max | 1.310 | 0.0218 | 0.017 |

### 특이사항

- 1회 호출 소요 시간은 재기동 후 첫 요청이라 참고값이다 (A 0.208초, B 0.077초, C 0.039초).
- `events_statements_history_long` consumer 는 baseline 측정 이후 컨테이너를 재시작하지 않아 YES 로 유지된 상태였다.

## step2: products(brand_id, deleted_at, amount, id DESC) 인덱스 (step1 누적)

### 변경

- 커밋 `0f4a094` feat: products(brand_id, deleted_at, amount, id DESC) 인덱스 추가 (step2)
- `Product` 엔티티 `@Table` 에 `@Index(name = "idx_products_brand_deleted_amount_id", columnList = "brand_id, deleted_at, amount, id DESC")` 1개 추가. step1 의 `idx_likes_product_id` 와 다른 코드는 그대로.
- Hibernate 6.6.11 은 `columnList` 의 ASC/DESC 를 파싱해 DDL 에 넣는다 (`IndexBinder` → `Index.selectableOrderMap` → `StandardIndexExporter`). 생성된 DDL: `create index idx_products_brand_deleted_amount_id on products (brand_id, deleted_at, amount, id desc)`. `SHOW INDEX FROM products` 에서 id 열 Collation `D` 확인.
- local·test 프로필은 `ddl-auto: create` 라 재기동·테스트 시 자동 생성된다. 다른 프로필은 별도 DDL 이 필요하다.
- 테스트: `./gradlew :apps:commerce-api:test --tests '*Like*' --tests '*Product*'` BUILD SUCCESSFUL, 109건 통과.

| 테스트 클래스 | 실행 | 실패 | 오류 | skip |
|---|---|---|---|---|
| LikeTest | 7 | 0 | 0 | 0 |
| LikeServiceTest | 7 | 0 | 0 | 0 |
| LikeFacadeTest | 9 | 0 | 0 | 0 |
| LikeV1ApiE2ETest | 10 | 0 | 0 | 0 |
| ProductTest | 12 | 0 | 0 | 0 |
| ProductSortTypeTest | 3 | 0 | 0 | 0 |
| ProductServiceTest | 22 | 0 | 0 | 0 |
| ProductRepositoryTest | 12 | 0 | 0 | 0 |
| ProductFacadeTest | 11 | 0 | 0 | 0 |
| ProductV1ApiE2ETest | 9 | 0 | 0 | 0 |
| AdminCrudV1ApiE2ETest (상품 중첩 클래스) | 7 | 0 | 0 | 0 |
| 합계 | 109 | 0 | 0 | 0 |

### 측정 환경 (`perf/step2/env.txt`)

2026-10-16 13:51 ~ 15:23 KST, 앱 코드 HEAD `0f4a094`. 앱은 13:50:40 KST 기동(PID 83905) 후 재기동 없음, MySQL 컨테이너도 재기동 없음. 옵션 없이 local 재기동 → seed → verify 20개 OK. 인덱스: products PRIMARY + `idx_products_brand_deleted_amount_id (brand_id, deleted_at, amount, id DESC)` / likes PRIMARY, `uk_likes_user_product`, `idx_likes_product_id`. 그 외 환경은 baseline 과 동일.

### 응답 일치

| 요청 | baseline 대비 응답 JSON | 캡처 SQL 문자열 | 계정 기준 SELECT 수 |
|---|---|---|---|
| A (brandId=7, price_asc) | 바이트 단위 동일 | 3개 동일 | 3 (baseline·step1 과 동일) |
| B (brandId=7, likes_desc) | 바이트 단위 동일 | 3개 동일 | 3 |
| C (필터 없음, price_asc) | 바이트 단위 동일 | 15개 동일 | 15 |

### SQL 수와 읽은 행 수 (1회 호출, performance_schema)

ms 는 참고값(1회).

| 요청 | SQL | SQL 수 (baseline / step1 / step2) | rows_examined baseline | step1 | step2 | ms 참고값(1회) baseline / step1 / step2 |
|---|---|---|---|---|---|---|
| A | 상품 목록 | 1 / 1 / 1 | 10,020 | 10,020 | 20 | 1.983 / 2.536 / 0.905 |
| A | 좋아요 집계 (IN 20) | 1 / 1 / 1 | 223,037 | 437 | 437 | 13.428 / 0.290 / 0.739 |
| A | 브랜드 조회 | 1 / 1 / 1 | 1 | 1 | 1 | 0.084 / 0.126 / 0.191 |
| B | 상품 목록 (likes_desc 정렬 서브쿼리) | 1 / 1 / 1 | 22,313,720 | 12,228 | 2,328 | 1294.861 / 8.568 / 2.443 |
| B | 좋아요 집계 (IN 20) | 1 / 1 / 1 | 223,037 | 850 | 850 | 13.564 / 0.303 / 0.260 |
| B | 브랜드 조회 | 1 / 1 / 1 | 1 | 1 | 1 | 0.112 / 0.108 / 0.143 |
| C | 상품 목록 (필터 없음) | 1 / 1 / 1 | 10,020 | 10,020 | 10,020 | 2.424 / 2.722 / 3.512 |
| C | 좋아요 집계 (IN 20) | 1 / 1 / 1 | 223,037 | 448 | 448 | 20.324 / 0.399 / 0.428 |
| C | 브랜드 조회 × 13 | 13 / 13 / 13 | 각 1 | 각 1 | 각 1 | 합 1.369 / 0.578 / 0.594 |

요청당 SQL 수는 A 3, B 3, C 15 로 세 단계 모두 같다. `no_index_used` 는 A·B 상품 목록 쿼리가 baseline·step1 의 1 에서 step2 에 0 이 됐고, C 상품 목록은 1 그대로다.

### EXPLAIN 변화 (type / key / rows / Extra)

| 요청 | SQL | baseline | step1 | step2 |
|---|---|---|---|---|
| A | 상품 목록 | ALL / NULL / 9955 / Using where; Using filesort | ALL / NULL / 9855 / 동일 | ref / idx_products_brand_deleted_amount_id / 100 / Using index condition |
| A | 좋아요 집계 | index / uk_likes_user_product / 222504 / Using where; Using index; Using temporary | range / idx_likes_product_id / 439 / Using where; Using index | range / idx_likes_product_id / 439 / 동일 |
| A | 브랜드 조회 | const / PRIMARY / 1 | const / PRIMARY / 1 | const / PRIMARY / 1 |
| B | 상품 목록 외부 | ALL / NULL / 9955 / Using where; Using filesort | ALL / NULL / 9855 / 동일 | ref / idx_products_brand_deleted_amount_id / 100 / Using index condition; Using filesort |
| B | 정렬 서브쿼리 (DEPENDENT SUBQUERY) | index / uk_likes_user_product / 222504 / Using where; Using index | ref / idx_likes_product_id / 24 / Using index | ref / idx_likes_product_id / 23 / Using index |
| B | 좋아요 집계 | index / uk_likes_user_product / 222504 / Using where; Using index; Using temporary | range / idx_likes_product_id / 850 / Using where; Using index | range / idx_likes_product_id / 850 / 동일 |
| B | 브랜드 조회 | const / PRIMARY / 1 | const / PRIMARY / 1 | const / PRIMARY / 1 |

C EXPLAIN (step2 에서만 기록, 전후 비교 아님. `perf/step2/C-explain.txt`, 브랜드 조회는 13개 중 id=2 하나만 대표로 포함)

| SQL | type / key / rows / Extra | EXPLAIN ANALYZE 주요 노드 (actual rows / loops / ms) |
|---|---|---|
| 상품 목록 (필터 없음) | ALL / NULL / 9700 / Using where; Using filesort | Table scan 10000 / 1 / 0.0621..1.63 → Filter(deleted_at is null) 10000 → Sort amount, id DESC (limit 20) 20 / 1 / 2.69 |
| 좋아요 집계 | range / idx_likes_product_id / 449 / Using where; Using index | Covering index range scan 448 / 1 / 0.0157..0.0792 → Group aggregate 19 / 1 / 0.0239..0.12 |
| 브랜드 조회 (id=2) | const / PRIMARY / 1 | Rows fetched before execution 1 / 1 / 0..0.000042 |

EXPLAIN ANALYZE 주요 노드 (actual rows / loops / actual time ms), step1 → step2

| 요청 | 노드 | step1 | step2 |
|---|---|---|---|
| A | 상품 목록: products 접근 | Table scan 10000 / 1 / 0.0317..1.94 → Filter 100 | Index lookup on idx_products_brand_deleted_amount_id (brand_id=7, deleted_at=NULL) 20 / 1 / 0.026..0.0385 |
| A | 상품 목록: Sort amount, id DESC (limit 20) | 20 / 1 / 2.32 | 노드 없음 (Limit 20 / 1 / 0.0305..0.0438) |
| A | 좋아요 집계: likes 접근 | Covering index range scan 437 / 1 / 0.00733..0.0575 | Covering index range scan 437 / 1 / 0.00767..0.0567 |
| A | 좋아요 집계: 집계 | Group aggregate 18 / 1 / 0.0154..0.0909 | Group aggregate 18 / 1 / 0.0161..0.0908 |
| B | 상품 목록: products 접근 | Table scan 10000 / 1 / 0.204..1.86 → Filter 100 | Index lookup on idx_products_brand_deleted_amount_id (brand_id=7, deleted_at=NULL) 100 / 1 / 0.0236..0.108 |
| B | 상품 목록: Sort (select #2) DESC, id DESC (limit 20) | 20 / 1 / 3.61 | 20 / 1 / 0.525..0.526 |
| B | 정렬 서브쿼리: likes 접근 | Covering index lookup 22.1 / 100 / 0.0106..0.012 | Covering index lookup 22.1 / 100 / 0.00207..0.0032 |
| B | 정렬 서브쿼리: Aggregate count | 1 / 100 / 0.013 | 1 / 100 / 0.00379..0.00381 |
| B | 좋아요 집계: likes 접근 | Covering index range scan 850 / 1 / 0.011..0.0785 | Covering index range scan 850 / 1 / 0.00717..0.0963 |
| B | 좋아요 집계: 집계 | Group aggregate 20 / 1 / 0.0225..0.133 | Group aggregate 20 / 1 / 0.0179..0.158 |

### A 응답 시간 (k6, 준비 50 + 측정 500 × 3회, 단위 ms)

VU 1명 회차별 (`perf/step2/k6-A-vu1-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 계정 기준 SELECT | digest 3종 합계 | 그 외 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 5.14 | 4.48 | 6.89 | 8.82 | 31.91 | 192.28 | 0 | 1500 | 1500 | 0 |
| 2 | 4.32 | 4.18 | 4.76 | 5.04 | 6.87 | 229.15 | 0 | 1500 | 1500 | 0 |
| 3 | 4.21 | 4.09 | 4.56 | 4.76 | 6.95 | 234.91 | 0 | 1500 | 1500 | 0 |

회차 간 편차 (범위 ÷ 평균): avg 20.4%, p50 9.2%, p90 43.1%, p95 65.4%, max 164.3%, req/s 19.5%.

VU 5명 회차별 (`perf/step2/k6-A-vu5-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 계정 기준 SELECT | digest 3종 합계 | 그 외 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 7.41 | 6.72 | 9.69 | 11.19 | 16.89 | 667.24 | 0 | 1500 | 1500 | 0 |
| 2 | 6.77 | 6.50 | 8.22 | 8.68 | 13.49 | 727.35 | 0 | 1500 | 1490 | 10 |
| 3 | 7.02 | 6.52 | 8.11 | 9.07 | 31.97 | 704.93 | 0 | 1500 | 1494 | 6 |

회차 간 편차 (범위 ÷ 평균): avg 9.1%, p50 3.3%, p90 18.2%, p95 26.0%, max 88.9%, req/s 8.6%. 요청당 SQL 수는 계정 기준 1,500 ÷ 500 = 3.000 (summary.tsv 의 digest 기준 sql_per_req 는 2.980 / 2.988, 특이사항 참고).

baseline · step1 · step2 (summary.tsv mean 행)

| 동시 요청 | 지표 | baseline | step1 | step2 | step2 ÷ baseline | step2 ÷ step1 |
|---|---|---|---|---|---|---|
| VU 1 | avg | 22.06 | 6.81 | 4.56 | 0.21 | 0.67 |
| VU 1 | p50 | 22.01 | 6.51 | 4.25 | 0.19 | 0.65 |
| VU 1 | p95 | 23.31 | 8.26 | 6.21 | 0.27 | 0.75 |
| VU 1 | max | 31.36 | 18.90 | 15.24 | 0.49 | 0.81 |
| VU 1 | req/s | 44.97 | 145.27 | 218.78 | 4.87 | 1.51 |
| VU 5 | avg | 25.45 | 8.52 | 7.07 | 0.28 | 0.83 |
| VU 5 | p50 | 24.93 | 8.17 | 6.58 | 0.26 | 0.81 |
| VU 5 | p95 | 30.24 | 10.54 | 9.65 | 0.32 | 0.92 |
| VU 5 | max | 48.44 | 18.08 | 20.78 | 0.43 | 1.15 |
| VU 5 | req/s | 195.16 | 581.48 | 699.84 | 3.59 | 1.20 |
| 공통 | 요청당 SQL (계정 기준) | 3.000 | 3.000 | 3.000 | 1.00 | 1.00 |

### B 응답 시간 (10회 순차 curl, 단위 초)

step2 10회: 0.0092, 0.0085, 0.0074, 0.0078, 0.0077, 0.0071, 0.0074, 0.0074, 0.0069, 0.0068 (모두 200). 10회 구간 계정 기준 SELECT 30, digest 3종 30 (10/10/10), 그 외 0.

| 지표 | baseline | step1 | step2 |
|---|---|---|---|
| avg | 1.186 | 0.0108 | 0.0076 |
| p50 | 1.175 | 0.0091 | 0.0074 |
| min | 1.128 | 0.0081 | 0.0068 |
| max | 1.310 | 0.0218 | 0.0092 |

### 특이사항

- 1회 호출 소요 시간은 재기동 후 첫 요청이거나 표본 1개라 참고값이다 (A 0.287초, B 0.075초, C 0.045초).
- VU5 2·3회차에서 digest 요약 표가 10·6건 누락. 계정 기준 SELECT 는 1,500 이고, 3회차는 문장 이력으로 1,500건 확인.
- step2 VU1 은 13:53 첫 실행을 버리고 14:50 에 재실행한 결과다. 첫 실행 기록은 남기지 않았다 (재실행 이유: 작업 세션이 끊겨(토큰 만료) 결과 확인을 이어가지 못함. 앱·DB 는 재기동하지 않았다).
- `events_statements_history_long` consumer 는 baseline 측정 이후 컨테이너를 재시작하지 않아 YES 로 유지된 상태였다.

## step3: 브랜드 IN 조회 (step1·step2 누적)

### 변경

- 커밋 `b2345e9` feat: 상품 목록 브랜드 조회를 IN 한 번으로 변경 (step3), `77ba157` fix: findAllByIdIn에 readOnly 트랜잭션 적용 (step2와 트랜잭션 조건 일치). 측정은 `77ba157` 기준.
- `ProductFacade.findBrandNames` (고객·관리자 목록 공용): 브랜드 id 마다 `brandRepository.findById` 를 호출하던 것을 `BrandRepository.findAllByIdIn(brandIds)` 한 번으로 교체. `BrandRepository`(domain)·`BrandJpaRepository`·`BrandRepositoryImpl`(infrastructure) 에 `findAllByIdIn` 추가. step1·step2 인덱스와 그 밖의 코드는 그대로.
- 계약 유지: 삭제된 브랜드도 이름을 그대로 노출하고, 브랜드 행이 없으면 브랜드명 null. 그래서 IN 쿼리에 deleted_at 조건을 넣지 않았다. 상품이 0개면 findBrandNames 전에 반환하므로 빈 IN 쿼리는 보내지 않는다.
- `BrandJpaRepository.findAllByIdIn` 에 `@Transactional(readOnly = true)` (77ba157). 인터페이스에 선언한 쿼리 메서드에는 `SimpleJpaRepository` 의 클래스 `@Transactional(readOnly = true)` 가 적용되지 않는다 (spring-data-commons 3.4.4 `TransactionalRepositoryProxyPostProcessor.computeTransactionAttribute`: 대상 클래스에 구현 메서드가 없으면 null). 붙이지 않으면 브랜드 조회가 autocommit 으로 실행돼 요청당 commit 3 → 2, set_option 12 → 8 로 step2 와 부수 문장 조건이 달라진다 (특이사항의 폐기분 참고).
- 테스트: `ProductFacadeTest` 의 `findById(1L)` 스텁을 `findAllByIdIn(eq(Set.of(1L)))` 로 교체 (`ReflectionTestUtils.setField` 로 Brand id 1L 설정). `AdminCrudV1ApiE2ETest.GetListsAsAdmin` 에 "브랜드 삭제 후 관리자 상품 목록에서 브랜드명이 그대로 나온다" 추가 (변경 전 코드도 만족하는 회귀 방지 테스트, IN 쿼리에 deleted_at 조건이 들어가면 실패). 삭제 브랜드명 노출·브랜드 없음 → null 을 직접 확인하는 단위 테스트는 추가하지 않았다.
- 테스트 명령: `./gradlew :apps:commerce-api:test --tests '*Brand*' --tests '*Product*' --tests '*Like*' --tests '*ArchitectureTest'` BUILD SUCCESSFUL (77ba157 기준 실행, 165건 통과, 실패·오류·skip 0).
- 측정 후 추가 (커밋 `2549586`, 아래 표에는 미포함): `ProductRepositoryTest.FindAll` 에 likes_desc 페이지 경계 테스트 "좋아요 많은 순 정렬은 페이지 안이 아니라 전체 기준으로 적용되어, 가장 늦게 저장된 상품이라도 좋아요가 가장 많으면 첫 페이지 첫 번째로 조회된다" (상품 3개, size 2, 좋아요 최다 상품을 마지막에 저장). 기존 동작 확인용이라 처음부터 통과. `./gradlew :apps:commerce-api:test --tests '*ProductRepositoryTest*'` FindAll 8건(기존 7 + 1) 통과, 실패·skip 0.
- 측정 후 추가 (커밋 `9ea4562`, 아래 표에는 미포함): SQL 개수 테스트 `ProductFacadeSqlCountTest` 2건 (브랜드 7 가격순 1페이지, 필터 없는 가격순 1페이지 → Hibernate `Statistics.getPrepareStatementCount()` 3) 과 도우미 `SqlCounter` (통계를 런타임에 켜고 reset/count/disable, 테스트 끝에 끔). 데이터는 `@Sql` 로 측정 seed 를 그대로 사용 (seed 1회 약 1.5 ~ 2초). Red 확인: step3 이전 코드 `3916e4b` 를 임시 worktree 에 두고 같은 테스트를 돌려 필터 없음이 `expected: 3 / but was: 15` 로 실패, 브랜드 7 은 3 으로 통과. 현 코드(`1ca13e0`)에서는 2건 모두 3 으로 통과.

| 테스트 클래스 | 실행 | 실패 | 오류 | skip |
|---|---|---|---|---|
| ProductFacadeTest | 11 | 0 | 0 | 0 |
| ProductServiceTest | 22 | 0 | 0 | 0 |
| ProductTest | 12 | 0 | 0 | 0 |
| ProductSortTypeTest | 3 | 0 | 0 | 0 |
| ProductRepositoryTest | 12 | 0 | 0 | 0 |
| ProductV1ApiE2ETest | 9 | 0 | 0 | 0 |
| AdminCrudV1ApiE2ETest (전체) | 19 | 0 | 0 | 0 |
| BrandFacadeTest | 10 | 0 | 0 | 0 |
| BrandFacadeIntegrationTest | 1 | 0 | 0 | 0 |
| BrandTest | 10 | 0 | 0 | 0 |
| BrandV1ApiE2ETest | 3 | 0 | 0 | 0 |
| AdminBrandV1ApiE2ETest | 3 | 0 | 0 | 0 |
| LikeFacadeTest | 9 | 0 | 0 | 0 |
| LikeServiceTest | 7 | 0 | 0 | 0 |
| LikeTest | 7 | 0 | 0 | 0 |
| LikeV1ApiE2ETest | 10 | 0 | 0 | 0 |
| ArchitectureTest | 3 | 0 | 0 | 0 |
| Order 계열 6 클래스 (메서드명에 Product/Like 가 들어가 패턴에 걸림) | 14 | 0 | 0 | 0 |
| 합계 | 165 | 0 | 0 | 0 |

브랜드 조회 SQL 문장 변경 (캡처 원문, 상품 목록·좋아요 집계 SQL 은 A·B·C 모두 step2 와 문자열 동일)

| 요청 | step2 | step3 |
|---|---|---|
| A·B | `select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id=7` | `select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id in (7)` |
| C | `select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id=2` 부터 `id=14` 까지 13문장 | `select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 where b1_0.id in (2,3,4,5,6,7,8,9,10,11,12,13,14)` 1문장 |

### 측정 환경 (`perf/step3/env.txt`)

2026-10-16 16:06 ~ 16:12 KST, 앱 코드 HEAD `77ba157`. 앱은 16:06 KST 기동(PID 91294) 후 재기동 없음, MySQL 컨테이너도 재기동 없음. 옵션 없이 local 재기동 → seed → verify 20개 OK. `SHOW INDEX` 로 step1 `idx_likes_product_id`, step2 `idx_products_brand_deleted_amount_id (brand_id, deleted_at, amount, id DESC)` 가 그대로 있고 brands 는 PRIMARY 만인 것을 확인. 그 외 환경은 baseline 과 동일. 측정 시작 전 API 호출 없음.

### 응답 일치

| 요청 | baseline 대비 응답 JSON | 캡처 SQL 문자열 | 계정 기준 SELECT 수 |
|---|---|---|---|
| A (brandId=7, price_asc) | 바이트 단위 동일 (2,625 bytes) | 상품 목록·좋아요 집계 2개 동일, 브랜드 `id=7` → `id in (7)` | 3 (step2 와 동일) |
| B (brandId=7, likes_desc) | 바이트 단위 동일 (2,651 bytes) | 2개 동일, 브랜드 `id=7` → `id in (7)` | 3 |
| C (필터 없음, price_asc) | 바이트 단위 동일 (2,598 bytes) | 2개 동일, 브랜드 13문장 → IN 1문장 | 3 (step2 는 15) |

### SQL 수와 읽은 행 수 (1회 호출, performance_schema)

ms 는 참고값(1회).

| 요청 | SQL | SQL 수 (baseline / step1 / step2 / step3) | rows_examined step2 | step3 | ms 참고값(1회) step2 / step3 |
|---|---|---|---|---|---|
| A | 상품 목록 | 1 / 1 / 1 / 1 | 20 | 20 | 0.905 / 1.063 |
| A | 좋아요 집계 (IN 20) | 1 / 1 / 1 / 1 | 437 | 437 | 0.739 / 1.036 |
| A | 브랜드 조회 | 1 / 1 / 1 / 1 | 1 | 1 | 0.191 / 0.421 |
| B | 상품 목록 (likes_desc 정렬 서브쿼리) | 1 / 1 / 1 / 1 | 2,328 | 2,328 | 2.443 / 2.403 |
| B | 좋아요 집계 (IN 20) | 1 / 1 / 1 / 1 | 850 | 850 | 0.260 / 0.349 |
| B | 브랜드 조회 | 1 / 1 / 1 / 1 | 1 | 1 | 0.143 / 0.144 |
| C | 상품 목록 (필터 없음) | 1 / 1 / 1 / 1 | 10,020 | 10,020 | 3.512 / 2.630 |
| C | 좋아요 집계 (IN 20) | 1 / 1 / 1 / 1 | 448 | 448 | 0.428 / 0.369 |
| C | 브랜드 조회 | 13 / 13 / 13 / **1** | 각 1 (합 13) | 13 | 합 0.594 / 0.363 |

요청당 SQL 수는 A 3, B 3 으로 네 단계 모두 같고, C 는 15 → 3. baseline·step1·step2 의 rows_examined 는 step2 절 표와 같다. 부수 문장은 A·B 가 commit 3 / set_option 12 / Ping 1 로 step2 와 같고, C 는 commit 15 → 3, set_option 60 → 12. `no_index_used` 는 변화 없음 (C 상품 목록만 1).

### EXPLAIN 변화 (type / key / rows / Extra)

A·B 의 SQL 3개는 select_type·type·possible_keys·key·key_len·ref·rows·Extra 가 step2 와 모두 같다. 브랜드 조회만 적는다.

| 요청 | SQL | step2 | step3 |
|---|---|---|---|
| A | 브랜드 조회 | const / PRIMARY / 1 / NULL | const / PRIMARY / 1 / NULL (`id in (7)` 은 const 로 처리) |
| B | 브랜드 조회 | const / PRIMARY / 1 / NULL | const / PRIMARY / 1 / NULL |
| C | 브랜드 조회 | const / PRIMARY / 1 / NULL × 13 (대표 id=2 만 기록) | range / PRIMARY / 13 / Using where |

C EXPLAIN step2 → step3 (`perf/step3/C-explain.txt`)

| SQL | step2 | step3 |
|---|---|---|
| 상품 목록 (필터 없음) | ALL / NULL / 9700 / Using where; Using filesort | 동일 |
| 좋아요 집계 | range / idx_likes_product_id / 449 / Using where; Using index | 동일 |
| 브랜드 조회 | const / PRIMARY / 1 / NULL (id=2, 13문장 중 1개) | range / PRIMARY / 13 / Using where (IN 13개, 1문장) |

C 브랜드 조회 step2 13문장 합 ↔ step3 1문장 (1회 캡처, performance_schema)

| | step2 `id=?` × 13 | step3 `id in (13개)` × 1 |
|---|---|---|
| 실행 횟수 | 13 | 1 |
| ms 합 (참고값) | 0.594 (0.027 ~ 0.160) | 0.363 |
| rows_examined 합 | 13 | 13 |
| rows_sent 합 | 13 | 13 |
| EXPLAIN ANALYZE | Rows fetched before execution 1 / 1 / 0..0.000042 (id=2) | Index range scan on PRIMARY 13 / 1 / 0.0126..0.0171 → Filter 13 / 1 / 0.013..0.0183 |

EXPLAIN ANALYZE 주요 노드 (actual rows / loops / actual time ms), step2 → step3

| 요청 | 노드 | step2 | step3 |
|---|---|---|---|
| A | 상품 목록: products 접근 | Index lookup on idx_products_brand_deleted_amount_id 20 / 1 / 0.026..0.0385 | 동일 노드 20 / 1 / 0.043..0.0548 |
| A | 상품 목록: Limit | 20 / 1 / 0.0305..0.0438 | 20 / 1 / 0.0493..0.0619 |
| A | 좋아요 집계: likes 접근 | Covering index range scan 437 / 1 / 0.00767..0.0567 | 437 / 1 / 0.00683..0.0505 |
| A | 좋아요 집계: 집계 | Group aggregate 18 / 1 / 0.0161..0.0908 | 18 / 1 / 0.0145..0.0825 |
| A | 브랜드 조회 | Rows fetched before execution 1 / 1 / 0.000042..0.000084 | 1 / 1 / 0.000084..0.000084 |
| B | 상품 목록: products 접근 | Index lookup on idx_products_brand_deleted_amount_id 100 / 1 / 0.0236..0.108 | 100 / 1 / 0.0312..0.121 |
| B | 상품 목록: Sort (select #2) DESC, id DESC (limit 20) | 20 / 1 / 0.525..0.526 | 20 / 1 / 0.55..0.551 |
| B | 정렬 서브쿼리: likes 접근 | Covering index lookup 22.1 / 100 / 0.00207..0.0032 | 22.1 / 100 / 0.00217..0.0033 |
| B | 정렬 서브쿼리: Aggregate count | 1 / 100 / 0.00379..0.00381 | 1 / 100 / 0.00391..0.00393 |
| B | 좋아요 집계: likes 접근 | Covering index range scan 850 / 1 / 0.00717..0.0963 | 850 / 1 / 0.00813..0.0961 |
| B | 좋아요 집계: 집계 | Group aggregate 20 / 1 / 0.0179..0.158 | 20 / 1 / 0.0194..0.158 |
| C | 상품 목록: products 접근 → Sort amount, id DESC (limit 20) | Table scan 10000 / 1 / 0.0621..1.63 → Sort 20 / 1 / 2.69 | Table scan 10000 / 1 / 0.0428..1.56 → Sort 20 / 1 / 2.57 |
| C | 좋아요 집계: likes 접근 → 집계 | Covering index range scan 448 / 1 / 0.0157..0.0792 → Group aggregate 19 / 1 / 0.0239..0.12 | 448 / 1 / 0.00838..0.0595 → 19 / 1 / 0.0147..0.0977 |

### A 응답 시간 (k6, 준비 50 + 측정 500 × 3회, 단위 ms)

VU 1명 회차별 (`perf/step3/k6-A-vu1-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 계정 기준 SELECT | digest 3종 합계 | 그 외 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 5.38 | 5.25 | 6.12 | 6.52 | 10.74 | 183.29 | 0 | 1500 | 1500 | 0 |
| 2 | 4.87 | 4.64 | 5.62 | 6.08 | 16.59 | 202.31 | 0 | 1500 | 1500 | 0 |
| 3 | 4.46 | 4.30 | 4.93 | 5.24 | 12.20 | 221.69 | 0 | 1500 | 1500 | 0 |

회차 간 편차 (범위 ÷ 평균): avg 18.8%, p50 20.1%, p90 21.4%, p95 21.5%, max 44.4%, req/s 19.0%. 회차당 commit 1,500 / set_option 6,000 (요청당 3 / 12) 으로 step2 와 같다.

VU 5명 회차별 (`perf/step3/k6-A-vu5-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 계정 기준 SELECT | digest 3종 합계 | 그 외 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 7.68 | 6.94 | 8.97 | 11.30 | 30.89 | 642.01 | 0 | 1500 | 1495 | 5 |
| 2 | 7.94 | 7.11 | 9.99 | 13.08 | 21.52 | 620.15 | 0 | 1500 | 1491 | 9 |
| 3 | 8.50 | 7.76 | 10.03 | 12.90 | 36.67 | 579.43 | 0 | 1502 | 1495 | 7 |

회차 간 편차 (범위 ÷ 평균): avg 10.2%, p50 11.3%, p90 11.0%, p95 14.3%, max 51.0%, req/s 10.2%. 요청당 SQL 수는 계정 기준 1,500 ÷ 500 = 3.000 (3회차는 1,502, 특이사항 참고. summary.tsv 의 digest 기준 sql_per_req 는 2.990 / 2.982 / 2.990).

baseline · step1 · step2 · step3 (summary.tsv mean 행)

| 동시 요청 | 지표 | baseline | step1 | step2 | step3 | step3 ÷ baseline | step3 ÷ step2 |
|---|---|---|---|---|---|---|---|
| VU 1 | avg | 22.06 | 6.81 | 4.56 | 4.90 | 0.22 | 1.07 |
| VU 1 | p50 | 22.01 | 6.51 | 4.25 | 4.73 | 0.21 | 1.11 |
| VU 1 | p95 | 23.31 | 8.26 | 6.21 | 5.95 | 0.26 | 0.96 |
| VU 1 | max | 31.36 | 18.90 | 15.24 | 13.18 | 0.42 | 0.86 |
| VU 1 | req/s | 44.97 | 145.27 | 218.78 | 202.43 | 4.50 | 0.93 |
| VU 5 | avg | 25.45 | 8.52 | 7.07 | 8.04 | 0.32 | 1.14 |
| VU 5 | p50 | 24.93 | 8.17 | 6.58 | 7.27 | 0.29 | 1.10 |
| VU 5 | p95 | 30.24 | 10.54 | 9.65 | 12.43 | 0.41 | 1.29 |
| VU 5 | max | 48.44 | 18.08 | 20.78 | 29.69 | 0.61 | 1.43 |
| VU 5 | req/s | 195.16 | 581.48 | 699.84 | 613.86 | 3.15 | 0.88 |
| 공통 | 요청당 SQL (계정 기준) | 3.000 | 3.000 | 3.000 | 3.000 | 1.00 | 1.00 |

A 는 step3 전후로 브랜드 조회 수(1)·트랜잭션 수(3)·SQL 수(3)가 같다. VU1(p50 4.25 → 4.73)·VU5(p50 6.58 → 7.27) 의 step2 ↔ step3 차이는 측정 편차로 보고 개선·악화로 적지 않는다.

### B 응답 시간 (10회 순차 curl, 단위 초)

step3 10회: 0.0088, 0.0074, 0.0075, 0.0084, 0.0073, 0.0067, 0.0067, 0.0066, 0.0063, 0.0064 (모두 200). 10회 구간 계정 기준 SELECT 30, digest 3종 30 (10/10/10), 그 외 0. commit 30 / set_option 120.

| 지표 | baseline | step1 | step2 | step3 |
|---|---|---|---|---|
| avg | 1.186 | 0.0108 | 0.0076 | 0.0072 |
| p50 | 1.175 | 0.0091 | 0.0074 | 0.0070 |
| min | 1.128 | 0.0081 | 0.0068 | 0.0063 |
| max | 1.310 | 0.0218 | 0.0092 | 0.0088 |

### 특이사항

- 1회 호출 소요 시간은 재기동 후 첫 요청이거나 표본 1개라 참고값이다 (A 0.294초, B 0.083초, C 0.030초).
- VU5 3회차 계정 기준 SELECT 1,502건 (기대 1,500). 초과 2건 중 1건은 digest 표의 `SELECT @@SESSION.transaction_read_only` 1건 (같은 회차 set_option 6,002 의 초과분 `SET character_set_results` 1건과 함께 나타나는 커넥션 초기화 문장) 으로 확인, 1건은 digest 표에 대응 행이 없어 미확인. 재측정하지 않았다. digest 3종 합계는 1,495 / 1,491 / 1,495 로 step2 VU5 와 같은 누락 양상.
- VU1 1회차 com/Ping 1, VU5 1회차 com/Ping 5 (계정 카운터 표에만 나타나고 SQL 수에 넣지 않음).
- 한계: 준비 50회를 버린 뒤에도 VU1 1회차가 2·3회차보다 느린 경향이 있다 (step3 avg 5.38 → 4.87 → 4.46, step2 5.14 → 4.32 → 4.21). mean 행은 1회차를 포함한 3회차 평균이다. 원인 후보는 재기동 직후 커넥션 풀이 연결을 만드는 중일 가능성이다 (step3 VU5 1회차 Ping 5). 준비 횟수를 늘려 사라지는지는 확인하지 않았다.
- 폐기분 (`perf/step3/discarded-no-tx/`, env + A 4 + k6 VU1·VU5 4): `77ba157` 적용 전 `b2345e9` 에서 1회 측정한 결과. 브랜드 조회가 트랜잭션 없이 실행돼 요청당 commit 2 / set_option 8 이었고, VU5 mean 은 p50 5.58 ms / 808.42 req/s 였다 (현 step3 7.27 / 613.86). 트랜잭션 문장 4개 감소의 영향 가능성만 적어 두며, 양쪽 모두 1회씩 잰 결과라 차이를 단정하지 않는다. B·C 측정 전에 중단했다. 응답·EXPLAIN·계정 기준 SELECT 3 은 현 step3 과 같았다.
- SQL 문장 이력을 남기는 performance_schema 설정(`setup_consumers` 의 `events_statements_history_long`)은 MySQL 8 기본값이 NO 이고 컨테이너를 재시작하면 NO 로 돌아간다. 측정 전 `ps-setup.sql` 로 YES 로 켜는데, baseline 측정 이후 MySQL 컨테이너를 재시작하지 않아 step3 에서도 켜기 전부터 YES 였다 (`perf/step3/A-sql.txt` 의 enabled_before = YES). 1회 호출 SQL 캡처(A·B·C-sql.txt)는 이 이력에서 읽는다.

## step4-cache: 상품 목록(가격순) Redis cache-aside (step1·step2·step3 누적)

### 변경

- 커밋: `2b3355d` test(Redis 테스트 컨테이너 주소 주입 시점), `ad47ebb` feat(캐시 전용 Redis 연결), `2719a03` feat(미삭제 상품 재고 일괄 조회), `4a4dc50` feat(cache-aside), `75e429e` feat(commit 후 무효화), `218486a`·`4118b85` test(실패·경쟁·장애 장면, 과거 주문 유지), `ee7c9ba` feat(연결 타임아웃 500ms), `57af583` feat(카운터), `67eef4b` chore(측정 스크립트). 측정은 `67eef4b` 기준.
- 캐시 조건: `brandId` 있음 + `sort=price_asc` + `size=20` + `page 0~2`. 그 외 요청(B·C 포함)은 기존 DB 경로 그대로.
- 키 `products:v1:brand={brandId}:sort=price_asc:page={page}:size=20`, TTL 30초(저장 시에만, 읽을 때 연장 없음), 값은 재고를 뺀 6필드(productId, productName, price, brandId, brandName, likeCount) JSON.
- miss: 기존 DB 경로(SQL 3) → 응답 → 저장. hit: 캐시 목록의 ID 20개로 `deleted_at IS NULL` 인 상품의 재고를 SQL 1개로 읽어 붙임. 재고 조회 건수가 캐시 건수와 다르면(삭제 상품 포함) DB 경로로 돌아가 다시 저장.
- 무효화: 각 쓰기 트랜잭션 안에서 등록하고 commit 뒤 해당 브랜드 page 0·1·2 키 DEL. 경로 7곳(상품 생성·수정·삭제, 브랜드 이름 수정·일괄 삭제, 좋아요 등록·취소). 재고 설정·주문 확정은 무효화하지 않는다(hit 가 재고를 DB 에서 읽음).
- Redis 오류: 조회 실패는 로그 후 miss 로 DB, 저장·삭제 실패는 로그만(쓰기 응답은 DB commit 대로). 캐시 전용 연결은 master 단독, 명령 타임아웃 100ms, 연결 타임아웃 500ms, 끊김 시 즉시 실패. 공통 모듈 `RedisConfig` 는 변경 없음.
- 카운터 `product_list_cache_total{result=hit|miss|hit_check_failed|find_error|save_error|evict_error}` (`/actuator/prometheus`, 8081).
- 테스트: `./gradlew :apps:commerce-api:check` 323건 통과, 실패·오류·skip 0, Checkstyle 위반 0 (`57af583` 기준).

### 측정 환경 (`perf/step4-cache/env.txt`)

2026-10-17 00:07 ~ 00:11 KST, 앱 코드 HEAD `67eef4b`. step3 앱(PID 91294)을 내리고 `./gradlew :apps:commerce-api:bootRun --console=plain` 으로 재기동(PID 7224, local 프로필). Redis 는 docker `redis-master`(7.0.15, 6379) + `redis-readonly`(6380) 를 기동했고 캐시는 master 만 쓴다. MySQL 컨테이너 재기동 없음. seed → verify 20개 OK → `products:v1:*` 키 0개 확인 → 측정. `SHOW INDEX` 는 step3 와 동일. 측정 시작 전 API 호출 없음.

### 응답 일치

| 요청 | baseline·step3 대비 응답 JSON | 계정 기준 SELECT 수 | 카운터 변화 |
|---|---|---|---|
| A miss (키 없음) | 바이트 단위 동일 (2,625 bytes) | 3 | miss +1 |
| A hit | 바이트 단위 동일 (2,625 bytes) | 1 | hit +1 |
| B (brandId=7, likes_desc) | 바이트 단위 동일 (2,651 bytes) | 3 | 없음 |
| C (필터 없음, price_asc) | 바이트 단위 동일 (2,598 bytes) | 3 | 없음 |

### SQL 수와 읽은 행 수 (1회 호출, performance_schema)

ms 는 참고값(1회). `ps-round-capture.sql` 은 이 단계부터 hit 재고 확인 SELECT 를 `stock_check` 로 따로 센다 (목록 SELECT 는 두 번째 열 `brand_id`, 재고 확인은 `quantity` 로 구분). 분류 확인: miss 1회 = product_list 1 / like_count 1 / brand_find 1 / stock_check 0, hit 1회 = 0 / 0 / 0 / 1.

| 요청 | SQL | SQL 수 | rows_examined | ms 참고값(1회) |
|---|---|---|---|---|
| A miss | 상품 목록 | 1 | 20 | 0.585 |
| A miss | 좋아요 집계 (IN 20) | 1 | 437 | 0.460 |
| A miss | 브랜드 조회 (IN 1) | 1 | 1 | 0.122 |
| A hit | 재고 확인 (`id IN 20 AND deleted_at IS NULL`) | 1 | 20 | 0.176 |
| B | 상품 목록 / 좋아요 집계 / 브랜드 조회 | 1 / 1 / 1 | 2,328 / 850 / 1 | 1.689 / 0.206 / 0.085 |
| C | 상품 목록 / 좋아요 집계 / 브랜드 조회 | 1 / 1 / 1 | 10,020 / 448 / 13 | 3.200 / 0.175 / 0.076 |

요청당 SQL 수는 A miss 3 (step3 과 같음), A hit 1, B 3, C 3. 부수 문장은 A miss 가 commit 3 / set_option 12 / Ping 1, A hit 가 commit 1 / set_option 4 (트랜잭션 3 → 1).

### EXPLAIN (hit 재고 확인 쿼리, `perf/step4-cache/hitcheck-explain.txt`)

| SQL | type / key / rows / Extra | EXPLAIN ANALYZE 주요 노드 (actual rows / loops / actual time ms) |
|---|---|---|
| 재고 확인 | range / PRIMARY / 20 / Using where | Index range scan on PRIMARY over 20 id 20 / 1 / 0.0446..0.0623 → Filter 20 / 1 / 0.0493..0.0686 |

A miss 의 SQL 3개는 step3 와 문자열이 같아 실행 계획도 같다.

### A 응답 시간 (k6, 준비 50 + 측정 500 × 3회, 단위 ms) — hit

회차마다 준비 50 직전에 캐시 키 1개를 지워 준비의 첫 요청이 다시 채우게 했다 (TTL 이 회차마다 새로 시작). 측정 500회 구간 카운터는 3회차 모두 hit 500 / miss 0 / 그 외 0.

VU 1명 회차별 (`perf/step4-cache/k6-A-hit-vu1-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | stock_check | 그 외 SELECT | 요청당 SQL |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 3.01 | 2.70 | 3.95 | 4.63 | 22.12 | 324.57 | 0 | 500 | 0 | 1.000 |
| 2 | 2.12 | 1.94 | 2.63 | 3.02 | 7.81 | 461.19 | 0 | 500 | 0 | 1.000 |
| 3 | 1.95 | 1.82 | 2.31 | 2.58 | 8.31 | 500.22 | 0 | 500 | 0 | 1.000 |

회차 간 편차 (범위 ÷ 평균): avg 44.9%, p50 40.9%, p95 60.1%, req/s 41.0%. 회차당 commit 500 / set_option 2,000 (요청당 1 / 4).

VU 5명 회차별 (`perf/step4-cache/k6-A-hit-vu5-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | stock_check (digest) | 그 외 SELECT | 요청당 SQL (digest) |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 3.13 | 2.90 | 4.08 | 4.55 | 5.96 | 1558.14 | 0 | 486 | 14 | 0.972 |
| 2 | 3.11 | 2.83 | 3.76 | 4.74 | 9.23 | 1570.14 | 0 | 485 | 15 | 0.970 |
| 3 | 3.02 | 2.88 | 3.65 | 4.03 | 6.08 | 1605.38 | 0 | 484 | 16 | 0.968 |

회차 간 편차: avg 3.6%, p50 2.4%, p95 16.0%, req/s 3.0%. 계정 기준 SELECT 는 3회차 모두 500 (요청당 1.000). digest 의 stock_check 가 484~486 으로 적게 잡힌 것은 step2·step3 VU5 와 같은 누락 양상이다 (특이사항).

step3 (캐시 전) · step4 hit (summary.tsv mean 행)

| 동시 요청 | 지표 | baseline | step3 | step4 hit | hit ÷ step3 | hit ÷ baseline |
|---|---|---|---|---|---|---|
| VU 1 | avg | 22.06 | 4.90 | 2.36 | 0.48 | 0.11 |
| VU 1 | p50 | 22.01 | 4.73 | 2.15 | 0.45 | 0.10 |
| VU 1 | p95 | 23.31 | 5.95 | 3.41 | 0.57 | 0.15 |
| VU 1 | max | 31.36 | 13.18 | 12.75 | 0.97 | 0.41 |
| VU 1 | req/s | 44.97 | 202.43 | 428.66 | 2.12 | 9.53 |
| VU 5 | avg | 25.45 | 8.04 | 3.09 | 0.38 | 0.12 |
| VU 5 | p50 | 24.93 | 7.27 | 2.87 | 0.39 | 0.12 |
| VU 5 | p95 | 30.24 | 12.43 | 4.44 | 0.36 | 0.15 |
| VU 5 | max | 48.44 | 29.69 | 7.09 | 0.24 | 0.15 |
| VU 5 | req/s | 195.16 | 613.86 | 1577.89 | 2.57 | 8.09 |
| 공통 | 요청당 SQL (계정 기준) | 3.000 | 3.000 | 1.000 | 0.33 | 0.33 |

miss 는 k6 로 재지 않았다 (요청 사이에 키를 지울 수 없음). miss 응답 시간은 아래 단발 반복만 있다.

### A 단발 100회 (curl, 단위 초, 참고값)

`single-calls.sh`. miss 는 매 호출 직전 키 DEL 후 호출, hit 는 준비 호출 1회(결과 버림) 뒤 호출. time_total 은 curl 프로세스 비용이 섞인 참고값이고, miss 쪽은 DEL 명령 시간이 호출 사이에 끼지만 time_total 에는 들어가지 않는다.

| 모드 | avg | p50 | p90 | min | max | 카운터 (100회) | 계정 기준 SELECT | digest |
|---|---|---|---|---|---|---|---|---|
| miss | 0.0093 | 0.0082 | 0.0097 | 0.0076 | 0.0525 | miss +100 | 300 | 목록 100 / 좋아요 100 / 브랜드 100 / stock_check 0 |
| hit | 0.0033 | 0.0032 | 0.0036 | 0.0029 | 0.0042 | hit +100 | 100 | 0 / 0 / 0 / 100 |

1회 캡처의 time_total 은 miss 0.181초(재기동 후 첫 요청), hit 0.067초 (참고값).

### 특이사항

- VU1 1회차가 2·3회차보다 느린 경향이 이번에도 있다 (p50 2.70 → 1.94 → 1.82, req/s 325 → 461 → 500). mean 행은 1회차를 포함한 평균이다. 원인은 step3 와 같이 확인하지 않았다.
- VU5 digest stock_check 484~486 (계정 기준 500). 그 외 SELECT 14~16 은 digest 표에 대응 행이 없는 누락분으로, step2·step3 VU5 의 양상과 같다. 재측정하지 않았다.
- A miss 1회 캡처의 문장 목록에 `SQL_TEXT: NULL` 1행이 있었다 (계정 기준 SELECT 3 에는 영향 없음). 원인은 확인하지 않았다.
- 측정 중 생긴 `products:v1:*` 키는 측정 뒤 그 키만 DEL 로 지웠다 (FLUSHALL 안 씀). 누적 카운터 최종값 hit 3,383 / miss 120 (k6 준비·단발·1회 캡처 포함).
- 측정하지 않은 것: miss 의 k6 동시 요청 측정, page 1·2 와 다른 브랜드의 hit, TTL 만료 시점이 측정 구간에 걸리는 경우(회차마다 키를 지워 피함).

## 측정하지 않은 범위

- 브랜드 필터 없는 likes_desc(B', `GET /api/v1/products?sort=likes_desc&page=0&size=20`)는 정식 측정 대상에 넣지 않았고 step3 이후 1회만 확인했다 (참고값(1회), 근거 `perf/step3/Bprime-sql.txt`·`Bprime-explain.txt`). 계정 기준 SELECT 3, 상품 목록 쿼리 rows_examined 233,057 (B 는 2,328), 정렬 서브쿼리 loops 10,000 (B 는 100), Sort 노드 30.3 ms (B 는 0.55 ms), 1회 호출 0.060초.
    - 필터가 없으면 전체 상품의 좋아요를 세어 정렬하므로 비용이 상품 수와 좋아요 수에 비례해 커진다.
    - like_count 비정규화로 줄일 수 있으나, 좋아요 쓰기의 정합성·동시성과 주문과의 잠금 순서 비용 때문에 이번 주에는 미뤘다.
    - 정렬 서브쿼리(상품마다 count)와 좋아요 집계(IN 20 group by)가 1페이지 상품 20개의 좋아요를 두 번 센다 (B' 는 5,470건, B 는 850건).
- 상품은 논리 삭제이고 복구 기능을 쓰지 않아 삭제 상품이 누적되므로 deleted_at 을 인덱스에 포함했다. 현재 seed 에는 삭제 상품이 없어 deleted_at 포함 효과는 측정하지 않았다.
- 인덱스가 brand_id 로 시작하므로 브랜드 필터가 없는 C(가격순 전체)는 이 인덱스로 정렬을 처리하지 못한다.
