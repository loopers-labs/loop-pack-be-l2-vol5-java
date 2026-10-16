# 좋아요 product_id 인덱스 적용 결과

기준선(`product-query-baseline.md`)에서 관찰한 `likes` 전체 스캔을 줄이기 위한 첫 변경이다.

- 실행일: 2026-10-15, 기준선과 같은 조건 · 같은 seed · 같은 실험 코드, **한 번 실행한 결과**
- 결과: 여섯 장면 모두 응답이 기대 결과와 일치 (인덱스 전후 결과 변화 없음)

## 1. 변경

```java
@Table(
    name = "likes",
    uniqueConstraints = @UniqueConstraint(name = "uk_likes_user_product", columnNames = {"user_id", "product_id"}),
    indexes = @Index(name = "idx_likes_product", columnList = "product_id")
)
```

| 후보 | 판단 |
|---|---|
| `likes(product_id)` 보조 인덱스 추가 | **채택.** 상품별 좋아요를 인덱스로 찾는다. 보조 인덱스에는 PK 가 들어 있어 `count(l.id)` 를 인덱스만으로 셀 수 있다(covering) |
| 유니크 키를 `(product_id, user_id)` 로 뒤집기 | 인덱스 하나로 끝나지만 "내 좋아요 목록"(`where user_id = ?`)과 좋아요 등록 · 취소의 관계 조회가 인덱스 앞 열을 잃는다 |

- 스키마는 `ddl-auto` 로 만들어지므로 기존 방식대로 엔티티에 선언했다.
- 대가: 좋아요 등록 · 취소마다 인덱스 갱신 1건과 저장 공간.

## 2. 전후 비교

| 장면 | p50 전(ms) | p50 후(ms) | p95 전(ms) | p95 후(ms) | 목록 SQL 전(ms) | 목록 SQL 후(ms) | 좋아요 조인 전(ms) | 좋아요 조인 후(ms) |
|---|---|---|---|---|---|---|---|---|
| 브랜드 7 · latest | 28.11 | 6.90 | 38.86 | 10.04 | 23.1 | 0.92 | 22.6 | 0.40 |
| 브랜드 7 · price_asc | 29.14 | 5.91 | 47.75 | 9.85 | 26.7 | 1.09 | 26.1 | 0.53 |
| 브랜드 7 · likes_desc | 29.17 | 5.38 | 39.50 | 8.37 | 24.3 | 0.77 | 23.8 | 0.35 |
| 전체 · latest | 208.08 | 204.61 | 249.91 | 304.84 | 215 | 201 | 39.4 | 32.1 |
| 전체 · price_asc | 185.18 | 178.01 | 211.34 | 193.52 | 183 | 179 | 38.2 | 32.3 |
| 전체 · likes_desc | 186.71 | 181.12 | 217.12 | 196.61 | 187 | 185 | 39.2 | 32.6 |

요청당 SQL 수는 전후 모두 2개다.

브랜드 7 · price_asc 의 바뀐 계획

```
-> Limit: 20 row(s)                                                       (actual time=1.09)
    -> Sort: p1_0.price, p1_0.id DESC, limit input to 20 row(s)           (rows=20)
        -> Table scan on <temporary>                                      (rows=94)
            -> Aggregate using temporary table                            (actual time=1.06 rows=94)
                -> Nested loop left join                                  (actual time=0.022..0.528 rows=959)
                    -> Filter: (p1_0.deleted_at is null)                  (rows=94)
                        -> Index lookup on p1_0 using FK(brand_id=7)      (rows=100)
                    -> Covering index lookup on l1_0 using idx_likes_product (product_id=p1_0.id)  (rows=10.1 loops=94)
```

## 3. 관찰

1. **브랜드 7에서는 목록 SQL이 약 24ms에서 약 1ms로 줄었다.** 해시 조인(likes 전체 100,163행)이 상품 94개마다 인덱스로 평균 10행을 읽는 중첩 루프 조인으로 바뀌었다. HTTP p50은 약 29ms에서 약 6ms가 됐다.
2. **전체 목록은 거의 그대로다(약 180~200ms).** 전체 목록은 원래 좋아요 95,606행을 모두 써야 하므로 인덱스가 읽는 양을 줄이지 못한다. 조인은 약 38ms에서 약 32ms로 조금 줄었지만, 비용의 대부분인 **9,412개 상품의 좋아요 수를 매번 임시 테이블로 집계하는 단계**(약 140~170ms)는 남았다.
3. 따라서 남은 병목은 "찾는 비용"이 아니라 "**첫 페이지 20개를 위해 후보 전체를 집계하는 비용**"이다. 인덱스로는 줄일 수 없으며, 집계를 줄이거나 없애는 구조 변경이 필요하다.

## 4. 남은 판단

- `latest` · `price_asc` 의 정렬 키는 좋아요 수와 무관하다. 그런데도 지금 쿼리는 집계를 먼저 하고 정렬한다.
- `likes_desc` 는 정렬 키 자체가 좋아요 수다. 전체 후보의 좋아요 수를 정렬 전에 알아야 한다.
- 이 차이에 따라 정렬마다 필요한 조치가 다를 수 있다. 판단은 별도로 기록한다.
