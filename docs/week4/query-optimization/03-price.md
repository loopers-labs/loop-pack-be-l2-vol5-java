# 가격순 목록 쿼리 개선

[목록으로](README.md)

**무필터 가격순 첫 페이지 20개의 API 평균 응답을 3,273.1ms에서 28.5ms로 줄였다.** 필터·가격 정렬에 맞는 복합 인덱스를 추가해 읽은 상품을 100만 행에서 21행으로 줄이고, 별도 정렬을 없앴다.

## 1. 인덱스를 사용해도 전체 후보를 읽고 정렬했다

**실행계획** — 무필터 첫 페이지의 변경 전 일반 EXPLAIN 요약이다. 아래 rows는 추정값이다.


| table | type | key                      | 추정 rows | Extra                                 |
| ----- | ---- | ------------------------ | -------: | ------------------------------------- |
| p     | ref  | idx_product_likes_filter | 496981  | Using index condition; Using filesort |


EC2 ANALYZE에서 확인한 실제 흐름은 다음과 같다. 원본을 핵심 노드만 남겨 요약했다.

```text
Limit: 21 rows (actual time=3641..3641 rows=21 loops=1)
  Sort: price ASC, id ASC
    Index lookup using idx_product_likes_filter (deleted_at=NULL)
      actual rows=1e+6 loops=1
```

**분석:** 최신순 인덱스가 있어도 가격순으로 정렬되어 있지는 않다. 삭제 여부·브랜드를 찾는 비용은 줄여도 가격 정렬을 위해 후보를 모두 읽었다. `Limit 21`은 최종 반환량이고 입력 21행만 읽었다는 뜻이 아니었다.

**읽기 흐름**

![가격순 개선 전](assets/price-before-read-flow.svg)

**판단과 변경:** 등치 조건 뒤에 실제 정렬 컬럼을 배치했다. 무필터와 브랜드 필터에 맞는 인덱스를 각각 추가했다.

```sql
-- 무필터
(deleted_at, price ASC, id ASC)
-- 브랜드 필터
(brand_id, deleted_at, price ASC, id ASC)
```

**결과 분석:** 무필터 목록 SQL은 3198.7 → 1.4ms로 줄었다. 아래에서 접근 경로와 실제 읽기 감소를 확인했다.

## 2. 정렬된 인덱스에서 21행만 읽고 멈췄다

**실행계획** — 변경 후 일반 EXPLAIN이다.


| table | type | key               | 추정 rows | Extra                 |
| ----- | ---- | ----------------- | -------: | --------------------- |
| p     | ref  | idx_product_price | 497566  | Using index condition |


EC2 ANALYZE 핵심 요약:

```text
Limit: 21 rows (actual completion=0.992ms, rows=21, loops=1)
  Index lookup using idx_product_price (deleted_at=NULL)
    actual rows=21 loops=1
```

**분석:** 일반 EXPLAIN의 추정 rows는 여전히 크지만, ANALYZE에서는 실제로 21행만 읽고 종료했다. 별도 Sort도 사라졌다. 선택 인덱스 이름만 보는 대신 실제 rows와 정렬 단계를 함께 확인했다.

**읽기 흐름**

![가격순 개선 후](assets/price-after-read-flow.svg)

**판단과 변경:** 해당 인덱스를 채택하고 JPA 매핑에도 반영했다. 상품명 등 응답 전체를 담은 covering index는 아니지만, 실제 상품 행 접근이 21개로 제한되어 목적을 충족했다.




| 조건      | 실제 읽은 상품 전 → 후 | 목록 SQL 전 → 후    | API 평균 전 → 후     |
| ------- | --------------: | ---------------: | ----------------: |
| 무필터     | 1,000,000 → 21 | 3,198.7 → 1.4ms | 3,273.1 → 28.5ms |
| 브랜드 1   | 5,000 → 21     | 147.1 → 1.4ms   | 186.4 → 24.1ms   |
| 브랜드 601 | 333 → 21       | 7.2 → 0.8ms     | 44.8 → 23.2ms    |

## 과제 필수 비교: 브랜드 필터 + 가격순

```http
GET /api/v1/products?brandId=1&sort=price_asc&page=0&size=20
```

동일한 서울 EC2 앱·상품 100만 개 데이터에서 재배포 없이 가격순 인덱스만 추가했다. 브랜드 1의 상품은 5,000개이며, 캐시 없이 각 조건 워밍업 3회 후 순차 3회 측정했다. 기본 page=0·size=20이며 21번째 행은 다음 페이지 여부 확인용이다.

| 비교 | 적용 전 | 적용 후 |
| --- | --- | --- |
| 사용 인덱스 | `idx_product_brand_latest` | `idx_product_brand_price` |
| 접근 경로 | 브랜드·삭제 조건 조회 → 가격·ID 정렬 | 브랜드·삭제 조건의 가격·ID 순서 조회 |
| 실제 rows / loops | 5,000 / 1 | 21 / 1 |
| 별도 정렬 | 있음 | 없음 |
| 별도 ANALYZE 완료 시간 | 14.1ms | 0.861ms |
| 요청당 SQL | 6회 | 6회 |
| 앱 계측 목록 SQL 평균 | 147.1ms | 1.4ms |

ANALYZE 시간과 앱 계측 시간은 별도 실행이며 합산하지 않는다. SQL 호출 수는 같고, 각 목록 SQL이 읽고 정렬하는 데이터가 줄었다.

| API 지연 표본(ms) | 적용 전 | 적용 후 |
| --- | ---: | ---: |
| 1회 | 170.409 | 25.820 |
| 2회 | 190.411 | 23.237 |
| 3회 | 198.267 | 23.098 |
| 최소 / 중앙 / 최대 | 170.409 / 190.411 / 198.267 | 23.098 / 23.237 / 25.820 |

세 응답 모두 HTTP 200이며, 반복 응답과 인덱스 전후 **전체 JSON이 일치**했다. 정렬된 상품 ID·가격·좋아요 수·페이지 정보를 포함하며, 브랜드 일치·중복 없음·상품 20개·`last=false`를 확인했다. 무필터와 브랜드 601도 전체 JSON 일치를 확인했다.

이는 당시 원본에서 확인한 표본이며 신규 측정이 아니다. 표본 3개로 p95·p99나 부하 한계를 추정하지 않는다. [추출한 전체 표본·응답 일치 기록](price-comparison-samples.json) · [데이터 재현 방법](../seed-data/README.md) · [적용 SQL](sql/add-price-indexes.sql)

브랜드 필터를 포함한 일반 EXPLAIN과 실행 SQL: [적용 전](sql/price-explain-before.txt) · [적용 후](sql/price-explain-after.txt). 로컬 MySQL 8.0.46에서 당시 쿼리·인덱스를 재구성한 결과이며 위 EC2 ANALYZE와 별도 실행이다.
