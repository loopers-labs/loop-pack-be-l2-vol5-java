# 상품 상세 조회 측정

캐시 대상(목록 vs 상세)을 정하기 전에 상세 조회의 원본 비용을 쟀다. 좋아요 수 비정규화 · `(brand_id, deleted_at)` 인덱스까지 적용한 상태다.

- 실험 코드: `ProductQueryExperiment.measureDetail` (같은 seed, 같은 측정 방식: 준비 50회 뒤 200회)
- 실행일: 2026-10-15, **한 번 실행한 결과**
- 요청: `GET /api/v1/products/{productId}`. 세 상품 모두 응답이 seed 규칙으로 계산한 기대 결과와 일치

## 1. 결과

| 상품 | 좋아요 수 | 요청당 SQL | 평균(ms) | p50(ms) | p95(ms) | p99(ms) | 최대(ms) |
|---|---|---|---|---|---|---|---|
| 6 (보통) | 5 | 1 | 2.97 | 2.87 | 3.84 | 5.34 | 5.42 |
| 7 (좋아요 0) | 0 | 1 | 2.90 | 2.77 | 3.66 | 4.33 | 13.97 |
| 97 (인기) | 250 | 1 | 2.79 | 2.69 | 3.77 | 5.16 | 6.99 |

```sql
select … from product p1_0
join brand b1_0 on b1_0.id = p1_0.brand_id
join product_like_count plc1_0 on plc1_0.product_id = p1_0.id
where p1_0.deleted_at is null and p1_0.id = ?
```

| table | type | key |
|---|---|---|
| p1_0 | const | PRIMARY |
| b1_0 | const | PRIMARY |
| plc1_0 | const | uk_product_like_count_product |

`EXPLAIN ANALYZE`: `Rows fetched before execution`.

## 2. 관찰

1. **세 테이블 모두 PK · 유니크 키 한 건 조회(`const`)다.** 옵티마이저가 계획을 세우는 단계에서 행을 이미 읽어, 실행 단계의 시간은 측정되지 않을 만큼 작다. 그래서 `EXPLAIN ANALYZE` 의 actual time 으로는 DB 비용을 비교할 수 없다. 계획 단계의 읽기는 이 시간에 포함되지 않는다.
2. ~~HTTP p50 약 2.7~2.9ms의 대부분은 DB 밖이다.~~ **캐시 측정 뒤 고침:** DB 를 거치지 않는 목록 hit 가 약 1ms였으므로, 2.8ms 중 약 1.5~2ms는 커넥션 획득 · 트랜잭션 · DB 왕복 비용이다. `EXPLAIN ANALYZE` 의 실행 시간에는 보이지 않는 비용이다(`product-query-cache.md` 관찰 4). 좋아요 수(0 / 5 / 250)와 관계없이 같다.
3. 목록과 비교하면, 브랜드 7 목록 p50 약 5ms, 전체 목록 약 9~24ms, 큰 브랜드 약 6~23ms다. 상세를 캐시하면 약 2.8ms → 약 1ms 로 줄 것으로 추정하지만(미측정), 절대값 2ms 미만이라 목록보다 이득이 작다.

## 3. 한계

- 한 번 실행한 결과이며, 동시 요청이 없는 상태의 지연이다.
- 상세 캐시의 hit 지연은 재지 않았다. 목록 hit(약 1ms, `product-query-cache.md`)로 추정했다.
