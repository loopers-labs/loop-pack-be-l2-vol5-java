# 좋아요 수 비정규화 적용 결과

`like-count-design.md` 의 변경(좋아요 수 행 inner join, GROUP BY 제거)을 적용한 뒤의 측정이다. 비교 대상은 `product-query-index.md` 의 "인덱스 적용 후" 값이다.

- 실행일: 2026-10-15, 같은 실험 코드 · 같은 seed, **한 번 실행한 결과**
- 결과: 아홉 장면 모두 응답이 seed 규칙으로 계산한 기대 결과와 일치 (변경 전후 결과 변화 없음)
- 큰 브랜드 장면은 기본 seed 장면을 모두 잰 뒤 `week4-large-brand-seed.sql` 을 더해 쟀다(`product-query-seed.md` 4절)

## 1. 전후 비교

| 장면 | p50 전(ms) | p50 후(ms) | p95 전(ms) | p95 후(ms) | 목록 SQL 전(ms) | 목록 SQL 후(ms) | 건수 SQL 후(ms) |
|---|---|---|---|---|---|---|---|
| 브랜드 7 · latest | 6.90 | 6.01 | 10.04 | 9.71 | 0.92 | 0.57 | 0.06 |
| 브랜드 7 · price_asc | 5.91 | 5.10 | 9.85 | 7.74 | 1.09 | 0.12 | 0.07 |
| 브랜드 7 · likes_desc | 5.38 | 4.80 | 8.37 | 7.01 | 0.77 | 0.29 | 0.05 |
| 전체 · latest | 204.61 | **9.13** | 304.84 | 14.03 | 201 | 4.63 | 2.04 |
| 전체 · price_asc | 178.01 | **8.41** | 193.52 | 13.46 | 179 | 3.43 | 1.80 |
| 전체 · likes_desc | 181.12 | **23.15** | 196.61 | 27.21 | 185 | 17.7 | 1.81 |
| 브랜드 101(18,824개) · latest | (미측정) | 28.80 | (미측정) | 43.10 | | 13.7 | 8.32 |
| 브랜드 101 · price_asc | (미측정) | 26.28 | (미측정) | 33.10 | | 11.9 | 8.86 |
| 브랜드 101 · likes_desc | (미측정) | **40.95** | (미측정) | 48.28 | | 32.2 | 8.74 |

요청당 SQL 수는 모든 장면에서 2개다.

## 2. 실행 계획

전체 · price_asc: 상품을 정렬한 뒤 20개만 브랜드 · 좋아요 수와 조인한다.

```
-> Limit: 20 row(s)                                        (actual time=3.43)
    -> Nested loop inner join                              (rows=20)
        -> Nested loop inner join                          (rows=20)
            -> Sort: p1_0.price, p1_0.id DESC              (actual time=3.38 rows=20)
                -> Filter: (p1_0.deleted_at is null) / Table scan on p1_0  (rows=10000)
            -> Single-row index lookup on b1_0 using PRIMARY            (loops=20)
        -> Single-row index lookup on plc1_0 using uk_product_like_count_product  (loops=20)
```

브랜드 101 · likes_desc: 브랜드 상품 전부를 좋아요 수 행과 조인한 뒤 정렬한다.

```
-> Limit: 20 row(s)                                                       (actual time=32.2)
    -> Sort: plc1_0.like_count DESC, plc1_0.product_id DESC, limit input to 20  (rows=20)
        -> Stream results                                                 (actual time=30.5 rows=18824)
            -> Nested loop inner join                                     (actual time=27.5 rows=18824)
                -> Filter: (p1_0.deleted_at is null)                      (rows=18824)
                    -> Index lookup on p1_0 using FK(brand_id=101)        (actual time=8.89 rows=20000)
                -> Single-row index lookup on plc1_0 using uk_product_like_count_product  (loops=18824)
```

## 3. 관찰

1. **전체 목록의 집계 비용이 사라졌다.** p50 약 180~205ms → 약 8~23ms. GROUP BY 임시 테이블이 없어졌고, `latest` · `price_asc` 는 정렬 후 20개만 조인한다.
2. **좋아요순은 정렬 전에 후보 전부를 좋아요 수 행과 조인한다.** 정렬 키가 다른 테이블에 있어서다. 전체 9,412개 조인에 약 15ms, 브랜드 101의 18,824개에 약 28ms가 든다. 그래서 같은 데이터에서 다른 정렬보다 2~3배 느리다.
3. **`(like_count, product_id)` 인덱스는 이 데이터에서 쓰이지 않았다.** 옵티마이저는 상품 쪽에서 출발해 조인한 뒤 정렬하는 계획을 골랐다. 인덱스 순서로 읽다가 20개에서 멈추는 계획은 선택되지 않았다.
4. **브랜드 + 좋아요순의 비용은 브랜드 상품 수에 비례한다.** 94개 0.29ms, 18,824개 32.2ms로, 상품 하나에 약 1.7µs다. 같은 브랜드의 `price_asc`(11.9ms)도 브랜드 상품 전부를 정렬하지만 조인이 20번뿐이라 더 빠르다.
5. **큰 브랜드에서는 건수 SQL도 약 8~9ms다.** FK 인덱스로 20,000행을 찾은 뒤 행마다 `deleted_at` 을 확인하려고 테이블을 읽는다.
6. 큰 브랜드 · latest 의 p99 · 최대(199.89ms · 472.82ms)는 다른 장면보다 튄다. 큰 seed 를 넣은 직후의 첫 장면이라 버퍼 풀 · GC 영향이 섞였을 수 있다(한 번 실행, 원인 미확인).

## 4. 판단

- **브랜드 + 좋아요순에 집계 병목은 없다.** 다만 브랜드 크기에 비례하는 조인 · 정렬 비용이 남는다. 18,824개 브랜드에서 p50 약 41ms다.
- 이 비용은 카운터를 별도 테이블에 두면서 감수하기로 한 것이다(`like-count-design.md` 2.1).
- 더 줄여야 한다면 다음 후보가 있으며, 이번에는 적용하지 않았다.

| 후보 | 줄이는 것 | 대가 |
|---|---|---|
| 카운터 테이블에 `brand_id` · 삭제 여부 복사 + `(brand_id, like_count, product_id)` 인덱스 | 브랜드 + 좋아요순을 인덱스 순서로 읽고 20개에서 멈춤 | 브랜드 일괄 삭제 · 상품 삭제 때 카운터 테이블도 갱신하는 책임 |
| 브랜드 + 좋아요순 첫 페이지 캐시 (이번 주 3단계) | 반복 요청의 조인 · 정렬 | 좋아요 · 상품 변경 때의 무효화 책임, miss 는 그대로 |
| 상품 쪽 `(brand_id, deleted_at, …)` 인덱스 | 큰 브랜드의 건수 SQL · `latest` · `price_asc` 정렬 | 상품 쓰기마다 인덱스 갱신 |

## 5. 한계

- 한 번 실행한 결과이며, 동시 요청이 없는 상태의 지연이다.
- 큰 브랜드는 비정규화 전 값을 재지 않았다. 브랜드 101은 이 측정을 위해 새로 더한 데이터다.
- 좋아요 등록 · 취소(쓰기) 쪽 지연과, 인기 상품에 좋아요가 몰릴 때의 카운터 행 대기는 재지 않았다.
