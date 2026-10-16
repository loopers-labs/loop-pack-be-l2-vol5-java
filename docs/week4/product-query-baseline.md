# 상품 목록 조회 기준선 (캐시 · 인덱스 변경 전)

인덱스 · 쿼리 · 캐시를 바꾸기 전의 측정 결과다. 이후 변경은 같은 조건으로 다시 재서 이 표와 비교한다.

- 실험 코드: `apps/commerce-api/src/test/java/com/loopers/experiment/ProductQueryExperiment.java`
- 실행: `RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*ProductQueryExperiment'` (평소 `check` 에서는 건너뜀)
- 원본 출력: `apps/commerce-api/build/experiment/product-query.md` (SQL 원문, EXPLAIN 표, EXPLAIN ANALYZE 트리)
- 실행일: 2026-10-15, 로컬 Docker의 MySQL 8.0.46 Testcontainers, **한 번 실행한 결과**

## 1. 조건

| 항목 | 값 |
|---|---|
| 데이터 | `product-query-seed.md` 의 seed. 실험 시작 시 `ANALYZE TABLE` 로 통계를 갱신 |
| 요청 | `GET /api/v1/products?[brandId=7&]sort={latest, price_asc, likes_desc}&page=1&size=20` (기존 계약 그대로). 브랜드 7(살아 있는 상품 94개)과 브랜드 필터 없는 전체(9,412개) |
| 결과 확인 | 응답의 id · 가격 · 좋아요 수 · 품절 여부와 페이지 정보를 seed 규칙으로 따로 계산한 기대 결과와 비교. 여섯 장면 모두 일치 |
| SQL 수 · 원문 | Hibernate `StatementInspector` 로 요청 한 번에 준비된 SQL 을 모음. 실행 계획에는 이 원문에 같은 인자를 넣어 실행 |
| 지연 | MockMvc 로 한 스레드에서 차례로 요청. 준비 50회 뒤 200회 측정. 네트워크 · 톰캣은 포함하지 않음 |
| 그 밖 | 테스트 커넥션 풀 10, SQL 출력(show-sql)은 측정에 섞이지 않도록 끔 |

## 2. 결과

목록 SQL 의 시간은 EXPLAIN ANALYZE 최상위(Limit) 노드의 actual time 이다. 하위 노드의 시간은 그 아래 작업을 포함한 누적값이다.

| 장면 | 요청당 SQL | 평균(ms) | p50(ms) | p95(ms) | p99(ms) | 최대(ms) | 목록 SQL(ms) | 그중 좋아요 조인(ms) | 건수 SQL(ms) |
|---|---|---|---|---|---|---|---|---|---|
| 브랜드 7 · latest | 2 | 29.92 | 28.11 | 38.86 | 59.11 | 75.85 | 23.1 | 22.6 | 0.08 |
| 브랜드 7 · price_asc | 2 | 31.79 | 29.14 | 47.75 | 64.98 | 81.55 | 26.7 | 26.1 | 0.80 |
| 브랜드 7 · likes_desc | 2 | 34.59 | 29.17 | 39.50 | 57.30 | 941.91 | 24.3 | 23.8 | 0.09 |
| 전체 · latest | 2 | 217.97 | 208.08 | 249.91 | 415.30 | 546.46 | 215 | 39.4 | 1.95 |
| 전체 · price_asc | 2 | 193.60 | 185.18 | 211.34 | 319.49 | 625.44 | 183 | 38.2 | 1.88 |
| 전체 · likes_desc | 2 | 192.05 | 186.71 | 217.12 | 246.57 | 312.54 | 187 | 39.2 | 1.78 |

브랜드 7 · price_asc 의 목록 SQL 계획 (정렬 단계 외에는 세 정렬이 같다)

```
-> Limit: 20 row(s)                                                   (actual time=26.7)
    -> Sort: p1_0.price, p1_0.id DESC, limit input to 20 row(s)       (actual time=26.7 rows=20)
        -> Table scan on <temporary>                                  (rows=94)
            -> Aggregate using temporary table                        (actual time=26.6 rows=94)
                -> Left hash join (l1_0.product_id = p1_0.id)         (actual time=19.8..26.1 rows=959)
                    -> Filter: (p1_0.deleted_at is null)              (rows=94)
                        -> Index lookup on p1_0 using FK(brand_id=7)  (rows=100)
                    -> Hash
                        -> Covering index scan on l1_0 using uk_likes_user_product  (rows=100163)
```

전체 · price_asc 의 목록 SQL 계획

```
-> Limit: 20 row(s)                                                   (actual time=183)
    -> Sort: p1_0.price, p1_0.id DESC, limit input to 20 row(s)       (actual time=183 rows=20)
        -> Table scan on <temporary>                                  (rows=9412)
            -> Aggregate using temporary table                        (actual time=180 rows=9412)
                -> Left hash join (l1_0.product_id = p1_0.id)         (actual time=18..38.2 rows=95606)
                    -> Nested loop inner join                         (rows=9412)
                        -> Filter: (p1_0.deleted_at is null) / Table scan on p1_0  (rows=10000)
                        -> Single-row index lookup on b1_0 using PRIMARY
                    -> Hash
                        -> Covering index scan on l1_0 using uk_likes_user_product  (rows=100163)
```

## 3. 관찰

1. **요청당 SQL은 2개이며 N+1은 없다.** 목록(상품 + 브랜드 + 좋아요 집계를 한 문장으로)과 건수다. 2주차에 집계를 한 쿼리로 묶어 두었기 때문이다.
2. **브랜드 7에서는 목록 SQL 시간의 대부분이 좋아요 조인이다.** 상품은 FK 인덱스로 100행만 읽지만, 좋아요는 실제로 필요한 959행을 얻으려고 **`likes` 전체 100,163행**을 유니크 키 `(user_id, product_id)` 로 훑어 해시 테이블을 만든다. 유니크 키의 첫 열이 `user_id` 라 `product_id` 로 찾는 조인에 쓰이지 못한다.
3. **전체 목록에서는 좋아요 조인(약 38ms)보다 집계가 더 크다.** 조인으로 나온 95,606행을 상품 · 브랜드 열 7개로 묶는 임시 테이블 집계에 약 140~170ms가 더 든다. 첫 페이지 20개를 얻기 위해 9,412개 상품 전부의 좋아요 수를 매번 센다.
4. **정렬 자체의 비용은 작다.** 집계가 끝난 뒤의 정렬 단계 시간은 0~3ms 수준이다. 세 정렬의 지연이 거의 같은 것도 비용이 정렬이 아니라 공통 단계(좋아요 조인 · 집계)에 있기 때문이다.
5. **브랜드 조건은 `brand.id = ?` 로 나가지만 상품의 FK 인덱스를 쓴다.** Querydsl이 `PRODUCT.brand.id` 를 조인한 brand 쪽 식별자로 바꾸지만, 옵티마이저가 조인 조건으로 상수를 넘겨 `product.brand_id = 7` 로 탐색했다.
6. **Querydsl은 첫 페이지에도 offset 을 넘긴다** (`limit ?,?`).
7. **건수 SQL은 전체에서도 2ms 안팎이다.** 전체 목록에서는 상품 테이블을 전부 읽지만 1만 행이라 작다.

## 4. 한계

- 한 번 실행한 결과이며, 동시 요청이 없는 상태의 지연이다. 최대값은 GC 등 일회성 지연이 섞일 수 있어 p95 · p99 를 함께 본다.
- 노트북 Docker 위의 MySQL이라 절대값보다 변경 전후의 상대 비교에 쓴다.
- 깊은 페이지는 재지 않았다.
