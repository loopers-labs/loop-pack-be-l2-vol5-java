# 상품 목록 조회 성능 개선 기록

## 1. 환경과 요청

| 항목 | 측정 조건 |
| --- | --- |
| DB | 로컬 Docker MySQL 8.0.46, `localhost:3306/loopers` |
| 데이터 | 브랜드 100개·사용자 1,000개·상품 1,000,000개·좋아요 5,194,998건 |
| 상품 상태 | 활성 988,000개·삭제 12,000개, 가격 합계 50,099,500,000 |
| 측정 브랜드 | 실제 PK `7`, 상품 10,000개·가격 합계 500,720,000 |
| 실행 환경 | macOS 15.6.1 arm64, 메모리 32 GiB·논리 CPU 10개 |
| 애플리케이션 | Java 21.0.9, Spring Boot 3.4.4, Hibernate 6.6.11.Final, `local` |
| DB 설정 | InnoDB buffer pool 128 MiB, `REPEATABLE-READ` |
| 캐시 | Redis 상품 캐시와 Hibernate query·2차 캐시 미사용 |
| HTTP 부하 측정 | k6 1.6.1 |

```http
GET /api/v1/products?brandId=7&page=0&size=20&sort=price_asc
```

| 파라미터 | API 기본값 | 측정값 |
| --- | --- | --- |
| `brandId` | 선택, 미지정 시 전체 브랜드 | `7` |
| `page` | `0` | `0` — 첫 페이지 |
| `size` | `20` | `20` |
| `sort` | `latest` | `price_asc` |

### 측정 방법

- k6는 단계별로 1·10·100 VU를 실행했다. 각 VU가 준비 3회를 마친 뒤 실측에 진입하며, 준비 요청을 제외한 전체 실측 1,000회를 집계했다.<br>
  VU별로 진행하므로 서로 다른 VU의 준비·실측 구간은 겹칠 수 있다.
- API는 같은 `bootRun` 설정으로 실행하고 SQL 상세 로그·Hibernate 통계는 껐다. k6는 `noConnectionReuse=true`를 사용했다.
- HTTP 지연은 요청 전송·서버 대기·응답 수신 시간이며 TCP 연결 수립 시간은 제외한다. 실패 응답도 지연 분포에 포함하며, p99.9는 1,000개 표본의 극소수 요청에 민감하다.
- SQL 수·DB 시간은 1 VU의 준비 포함 1,003개 요청에 대한 Performance Schema 차이로 계산했다. 요청당 DB 시간은 누적 시간을 1,003으로 나눈 값이며 HTTP 지연과 측정 범위가 다르다.

2절 비교표는 각 단계·VU별 한 번의 측정 결과다. DB·OS 캐시를 초기화하지 않았으며, 인덱스 생성과 같은 호스트의 자원 공유 영향을 포함한다.<br>
100 VU도 실측 1,000회로 종료하는 단기 측정이다. 지속 부하 측정은 2절의 "100 VU 평균 미달 원인"에 따로 정리했다.<br>
SQL 비용·실행계획의 전후 값은 각 개선 실험에서 수집했으며, 이전 상태의 재측정 원본은 단계별 `results.json`에 보관한다.

### 측정 자료

| 구분 | 실행 SQL·스크립트 | 측정 원본 |
| --- | --- | --- |
| 공통 | [seed](../../apps/commerce-api/src/test/java/com/loopers/fixture/ProductQuerySeed.java) · [스키마](../../docker/mysql/init.sql) · [k6](../k6/product-query.k6.js) | [기준 응답](../product-query-baseline/response-canonical.json) |
| 기준선 | [queries.sql](../product-query-baseline/queries.sql) | [EXPLAIN](../product-query-baseline/explain.sql) · [EXPLAIN ANALYZE](../product-query-baseline/explain-analyze.sql) |
| 1단계 | [queries.sql](../product-query-step1/queries.sql) | [results.json](../product-query-step1/results.json) |
| 2단계 | [product-query-index.sql](../../docker/mysql/product-query-index.sql) | [results.json](../product-query-step2/results.json) |
| 3단계 | [product-query-list-index.sql](../../docker/mysql/product-query-list-index.sql) | [results.json](../product-query-step3/results.json) |
| 4단계 | [queries.sql](../product-query-step4/queries.sql) | [results.json](../product-query-step4/results.json) · [부하 원인·likes_desc 진단](../product-query-step4/final-state-diagnosis.json) |

## 2. 단계별 결과 비교

**자체 목표: 100 VU·실측 1,000회에서 평균 ≤ 20 ms·p99 ≤ 200 ms·HTTP 실패율 0%·응답 검증 100%.**<br>
네 조건을 모두 만족해야 목표 달성으로 판단한다. k6 종료 코드만으로 시간 목표 충족을 판단하지 않는다.

### 성능 비교 (1 VU)

| 단계 | 변경 | SQL/요청 | 최소 | 평균 | p50 | p95 | p99 | p99.9 | 최대 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 기준선 | 개선 전 | 22 | 152.468 ms | 164.350 ms | 159.484 ms | 186.122 ms | 283.177 ms | 352.625 ms | 373.309 ms |
| 1단계 | UNION ALL 좋아요 일괄 조회 | 3 | 127.805 ms | 136.111 ms | 132.980 ms | 151.376 ms | 218.356 ms | 239.042 ms | 250.210 ms |
| 2단계 | 좋아요 product_id 단일 인덱스 | 3 | 48.373 ms | 54.993 ms | 54.326 ms | 59.983 ms | 62.923 ms | 68.258 ms | 71.657 ms |
| 3단계 비교안 | 상품 price 단일 인덱스 (측정 후 제거) | 3 | 2.748 ms | 6.258 ms | 5.697 ms | 12.135 ms | 14.546 ms | 15.642 ms | 16.298 ms |
| 3단계 | 상품 브랜드·삭제·가격 복합 인덱스 | 3 | 2.117 ms | 4.907 ms | 4.155 ms | 8.709 ms | 11.190 ms | 16.209 ms | 22.301 ms |
| 4단계 | 좋아요 IN + GROUP BY 전환 | 3 | 2.072 ms | 4.310 ms | 3.723 ms | 7.710 ms | 9.987 ms | 13.659 ms | 20.677 ms |

모든 단계의 1 VU 실측 1,000회는 HTTP 실패율 0%·응답 검증 100%였다.

### 동시 부하 비교 (10 VU)

| 단계 | 최소 | 평균 | p50 | p95 | p99 | p99.9 | 최대 | HTTP 실패율 | 응답 검증 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 기준선 | 346.916 ms | 395.258 ms | 371.398 ms | 586.954 ms | 661.000 ms | 680.595 ms | 715.004 ms | 0% | 100% |
| 1단계: UNION ALL | 337.739 ms | 356.698 ms | 351.486 ms | 396.375 ms | 415.616 ms | 417.345 ms | 418.150 ms | 0% | 100% |
| 2단계: 좋아요 단일 | 113.505 ms | 122.819 ms | 119.517 ms | 145.973 ms | 158.123 ms | 159.599 ms | 160.056 ms | 0% | 100% |
| 3단계 비교안: 상품 가격 단일 | 2.452 ms | 9.828 ms | 9.505 ms | 14.138 ms | 16.979 ms | 23.912 ms | 25.485 ms | 0% | 100% |
| 3단계: 상품 복합 | 4.037 ms | 6.966 ms | 6.687 ms | 9.888 ms | 11.534 ms | 13.173 ms | 15.541 ms | 0% | 100% |
| 4단계: IN + GROUP BY | 3.785 ms | 7.128 ms | 6.875 ms | 9.670 ms | 11.373 ms | 14.138 ms | 14.931 ms | 0% | 100% |

### 목표 달성 확인 (100 VU)

| 단계 | 최소 | 평균 | p50 | p95 | p99 | p99.9 | 최대 | HTTP 실패율 | 응답 검증 | 종합 판정 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 기준선 | 1,084.315 ms | 3,429.975 ms | 3,377.774 ms | 4,682.444 ms | 4,989.746 ms | 5,164.483 ms | 5,215.069 ms | 1.7% | 98.3% | 미달 |
| 1단계: UNION ALL | 963.939 ms | 2,864.133 ms | 2,887.288 ms | 3,249.200 ms | 3,360.373 ms | 3,528.374 ms | 3,533.476 ms | 0.6% | 99.4% | 미달 |
| 2단계: 좋아요 단일 | 59.017 ms | 1,073.435 ms | 1,285.584 ms | 1,449.193 ms | 1,901.418 ms | 2,352.602 ms | 2,799.101 ms | 0% | 100% | 미달 |
| 3단계 비교안: 상품 가격 단일 | 5.356 ms | 86.865 ms | 86.539 ms | 134.404 ms | 165.703 ms | 222.852 ms | 232.384 ms | 0% | 100% | 평균 미달 |
| 3단계: 상품 복합 | 3.376 ms | 61.885 ms | 59.828 ms | 113.247 ms | 151.468 ms | 190.798 ms | 198.084 ms | 0% | 100% | 평균 미달 |
| 4단계: IN + GROUP BY | 2.405 ms | 60.431 ms | 59.758 ms | 97.635 ms | 116.059 ms | 139.389 ms | 144.124 ms | 0% | 100% | 평균 미달 |

3단계(비교안 포함)와 4단계는 p99·실패율·응답 검증을 충족했고, 평균 20 ms 조건은 아직 미달이다.<br>
4단계 행은 UNION ALL과 교차 측정한 두 회차 중 1회차 값이다. 두 회차와 대조 측정값은 7절에 정리했다.

### 100 VU 평균 미달 원인

k6의 각 VU는 응답을 받자마자 다음 요청을 보내므로, 평균 지연은 `VU 수 ÷ 초당 처리량`에 가깝다. 100 VU에서 평균 20 ms를 달성하려면 초당 약 5,000건을 처리해야 한다.<br>
4단계 상태에서 앱을 다시 띄우고 1 VU 5,000회로 준비한 뒤, 1 VU 5,000회·100 VU 50,000회를 지속 측정해 원인을 확인했다.

| 항목 (요청당 평균) | 1 VU | 100 VU |
| --- | ---: | ---: |
| HTTP 지연 | 1.808 ms | 39.264 ms |
| 처리량 | 495 req/s | 2,502 req/s |
| 커넥션 획득 대기 | 0.000 ms | 23.051 ms |
| 커넥션 사용 시간 | 1.624 ms | 15.794 ms |
| MySQL 실행 시간 (문장 7개 합) | 0.424 ms | 1.816 ms |

- **커넥션 풀 포화:** 100 VU 동안 Hikari 커넥션 40개가 계속 모두 사용 중이었고, 커넥션을 기다리는 스레드가 55~60개였다. 평균 지연의 약 60%가 커넥션 대기다.
- **커넥션 점유 시간의 대부분은 MySQL 실행 밖:** 커넥션을 잡고 있는 15.794 ms 중 MySQL 실행은 1.816 ms다.<br>
  요청당 7번의 왕복(조회 3개, `autocommit`·읽기 전용 설정 4개)과 커넥션을 쥔 채 수행하는 애플리케이션 처리가 나머지를 차지한다. 둘의 비중은 측정하지 않았다.
- **같은 PC의 자원 공유:** 100 VU 측정 중 MySQL 컨테이너 약 4.0코어, 앱 약 1.5코어, k6 약 0.9코어를 사용했다(논리 CPU 10개).

따라서 SQL·인덱스를 더 줄여도 평균 20 ms에 도달하기 어렵다. 남은 수단은 커넥션 점유 시간 단축(왕복 수 감소), DB 접근 자체를 줄이는 캐시, 처리 자원 확대다.

1,000회 100 VU 측정은 약 1.4초로 짧아 JVM 준비 상태에 민감하다.<br>
앱 시작 직후 바로 측정하면 평균 84.111 ms, 충분히 준비한 뒤 지속 측정하면 평균 39.264~46.215 ms였다. 위 비교표의 100 VU 값은 같은 앱에서 1·10 VU 측정 직후 실행한 단기 값이다.

응답 검증은 HTTP 200·`meta.result=SUCCESS`·상품 20개·정렬된 필드 값의 일치를 확인한다.<br>
기준 응답의 `id`, `brandId`, `brandName`, `name`, `price`, `likeCount` 순서와 상품 반환 순서를 고정해 체크섬을 비교했다.

## 3. 개선 전 기준선

### 요청당 SQL 수

상품 목록 1회, 브랜드 일괄 조회 1회, 상품별 좋아요 COUNT 20회로 **요청당 조회 SQL은 22회**였다.<br>

```sql
SELECT p.id, p.brand_id, p.created_at, p.deleted_at,
       p.name, p.price, p.stock, p.updated_at
FROM products p
LEFT JOIN brands b ON b.id = p.brand_id
WHERE p.deleted_at IS NULL AND b.id = 7
ORDER BY p.price, p.id
LIMIT 20;
```

```sql
SELECT b.id, b.created_at, b.deleted_at, b.name, b.updated_at
FROM brands b
WHERE b.id IN (7);
```

```sql
SELECT COUNT(l.id)
FROM likes l
WHERE l.product_id = 5406;
```

### EXPLAIN

`rows`는 예상 조회 행 수, `filtered`는 조건 통과율의 추정치다. 실제 행 수·시간은 ANALYZE에서 확인한다.

#### 상품 목록 조회

| 테이블 | 접근 방식 | 사용 key | 예상 rows·filtered | Extra |
| --- | --- | --- | ---: | --- |
| `b` (브랜드 조인) | `const` | `PRIMARY` | 1·100% | `Using index; Using filesort` |
| `p` (상품) | `ref` | `FKa3a4mpsfdf4d2y6r8ra3sc8mv` | 17,440·10% | `Using where` |

브랜드 `7`을 PK로 먼저 찾고, 상품의 `brand_id` 인덱스로 연결한다.<br>
상품은 17,440행 중 10%인 약 1,744행이 삭제 조건을 통과할 것으로 추정했다.<br>
가격·ID 순서는 이 인덱스로 해결되지 않아 별도 정렬이 필요하다. `Using filesort`는 `b` 행에 표시됐지만 정렬 대상은 상품이다.

#### 브랜드 일괄 조회

| 테이블 | 접근 방식 | 사용 key | 예상 rows·filtered | Extra |
| --- | --- | --- | ---: | --- |
| `b` (브랜드) | `const` | `PRIMARY` | 1·100% | — (`NULL`) |

페이지 상품의 브랜드가 모두 `7`이라 `IN (7)`로 한 건을 조회한다. 브랜드 필터가 없으면 여러 ID가 전달될 수 있다.

#### 좋아요 COUNT

| 테이블 | 접근 방식 | 사용 key | 예상 rows·filtered | Extra |
| --- | --- | --- | ---: | --- |
| `l` (좋아요) | `range` · skip scan | `uk_like_user_product(user_id, product_id)` | 504,460·100% | `Using where; Using index for skip scan` |

조건은 `product_id`뿐인데 인덱스는 `(user_id, product_id)` 순서라 skip scan을 선택했다.<br>
504,460은 옵티마이저 추정치이며 실제 좋아요 수나 COUNT 결과가 아니다.

### EXPLAIN ANALYZE

연산자 시간에는 하위 실행이 포함되므로 합산하지 않는다. 아래 표는 데이터가 흐르는 순서다.

#### 상품 목록 조회

| 연산자 (아래에서 위로) | 예상 rows | 실제 rows | loops | 실제 시간 |
| --- | ---: | ---: | ---: | ---: |
| 상품 인덱스 조회 (`brand_id=7`) | 17,440 | 10,000 | 1 | 1.86–71.5 ms |
| 삭제 조건 필터 | 17,440 | 10,000 | 1 | 1.88–71.9 ms |
| `price, id` 정렬 | 17,440 | 20 | 1 | 72.7–72.7 ms |
| `LIMIT 20` | 20 | 20 | 1 | 72.7–72.7 ms |

상품 10,000개가 모두 삭제 조건을 통과했고, 정렬 단계가 가격·ID 기준 상위 20개를 반환했다. 전체 실행은 72.7 ms였다.<br>
이 표의 예상 행 수는 ANALYZE의 TREE 출력값이며, 앞의 일반 EXPLAIN에서 계산한 1,744행과 구분한다.

#### 브랜드 일괄 조회

| 연산자 | 예상 rows | 실제 rows | loops | 실제 시간 |
| --- | ---: | ---: | ---: | ---: |
| 브랜드 조회 (`id=7`) | 1 | 1 | 1 | 기준 출력에 없음 |

실행 전에 브랜드 한 건을 가져오는 계획이며, 기준 출력에는 개별 소요 시간이 없다.

#### 좋아요 COUNT

| 연산자 (아래에서 위로) | 예상 rows | 실제 rows | loops | 실제 시간 |
| --- | ---: | ---: | ---: | ---: |
| 복합 인덱스 skip scan (`product_id`) | 504,460 | 상품별 0~10 | 1 | 기준 출력에 없음 |
| COUNT 집계 (`COUNT(l.id)`) | 1 | 1 | 1 | 첫 실행 25.5 ms |

상품별로 좋아요 0~10개를 찾으며, COUNT는 좋아요가 없어도 값 `0`인 결과 한 행을 반환한다.<br>
상품마다 별도 SQL을 실행하므로 목록 요청에서는 20회 반복된다. 아래 반복 집계는 단건 ANALYZE의 25.5 ms와 별도 측정이다.

| 측정 요청 수 | COUNT 호출 수 | 누적 DB 실행 시간 | 요청당 좋아요 조회 시간 (평균) |
| ---: | ---: | ---: | ---: |
| 1,003회 | 20,060회 | 101,000.601 ms | 100.699 ms |

### 실행계획 기준 개선 판단

| 조회 | 판단 | 근거 |
| --- | --- | --- |
| 상품 목록 | 개선 후보 | 20개를 반환하지만 상품 10,000개를 읽고 `price, id`로 정렬했다. |
| 브랜드 일괄 조회 | 현재 요청에서는 추가 개선 근거 없음 | 브랜드 ID 한 개를 기본 키로 한 번 조회해 한 행을 반환했다. |
| 좋아요 COUNT | 개선 후보 | 상품마다 COUNT를 실행해 요청당 20회 발생했고, `product_id` 조건에 복합 인덱스 skip scan을 사용했다. |
| 좋아요순 목록 (`likes_desc`) | 측정 대상 밖, 남은 병목 | 정렬 키가 상품별 좋아요 COUNT라, 후보 상품 전체에 COUNT를 실행한 뒤 정렬한다. |

`likes_desc`는 최종 인덱스 상태에서 따로 측정했다. EXPLAIN ANALYZE 3회 기준 브랜드 7은 상품 10,000개에 COUNT를 실행해 89.7~209 ms,<br>
브랜드 필터가 없으면 활성 상품 988,000개에 실행해 1,642~1,682 ms였다.<br>
정렬 키가 계산값이라 인덱스로는 해결되지 않는다. 좋아요 수를 미리 저장하는 비정규화는 좋아요 쓰기 경로와 동시성 처리까지 바뀌므로 이번 범위에서 적용하지 않았다.

## 4. 1단계: UNION ALL 좋아요 일괄 조회

상품별 COUNT 20개를 Native SQL의 `UNION ALL`로 연결했다.<br>
중복 ID는 제거하고 파라미터로 바인딩한다. 빈 페이지에서는 좋아요 SQL을 생략하고, 좋아요가 없는 상품은 `0`을 반환한다.

### 요청당 SQL 수와 좋아요 조회 비용

| 조회 | 적용 전 호출 수 | 적용 후 호출 수 | 적용 전 요청당 평균 DB 시간 | 적용 후 요청당 평균 DB 시간 |
| --- | ---: | ---: | ---: | ---: |
| 상품 목록 | 1,003 | 1,003 | 40.032 ms | 42.824 ms |
| 브랜드 일괄 조회 | 1,003 | 1,003 | 0.141 ms | 0.147 ms |
| 좋아요 수 | 20,060 | 1,003 | 88.934 ms | 84.063 ms |

요청당 SQL은 **22회 → 3회**, 좋아요 SQL 왕복은 **20회 → 1회**로 줄었다.<br>
UNION 내부의 상품별 검색·COUNT는 여전히 20회다. 상품·브랜드 조회, 필터·정렬·페이지·삭제 조건은 유지했다.

### EXPLAIN

| 조회·테이블 | 단계 | 접근 방식 | 사용 key | 예상 rows·filtered | Extra |
| --- | --- | --- | --- | ---: | --- |
| 좋아요 `l` | 적용 전 개별 COUNT | `range` · skip scan | `uk_like_user_product` | 504,460·100% | `Using where; Using index for skip scan` |
| 좋아요 `l` | 적용 후 UNION의 각 분기 | `range` · skip scan | `uk_like_user_product` | 분기당 504,460·100% | `Using where; Using index for skip scan` |

20개 분기 모두 기존 `(user_id, product_id)` 인덱스의 skip scan을 사용했다. 상품·브랜드의 접근 경로는 기준선과 같다.

### EXPLAIN ANALYZE

첫 분기(`product_id=5406`)와 전체 UNION의 결과다.

| 연산자 (아래에서 위로) | 예상 rows | 실제 rows | loops | 실제 시간 |
| --- | ---: | ---: | ---: | ---: |
| 첫 분기 복합 인덱스 skip scan | 504,460 | 5 | 1 | 0.217–5.83 ms |
| 첫 분기 상품 ID 필터 | 504,460 | 5 | 1 | 0.218–5.83 ms |
| 첫 분기 COUNT | 1 | 1 | 1 | 5.84–5.84 ms |
| 첫 분기 결과 반환 | 1 | 1 | 1 | 5.84–5.84 ms |
| 전체 UNION 결과 연결 (`Append`) | 20 | 20 | 1 | 5.84–77 ms |

각 분기는 좋아요 0~10개를 찾아 COUNT 결과 한 행을 반환하고, 전체 UNION은 20행을 반환했다.<br>
각 분기의 `loops=1`과 별개로 쿼리에는 COUNT 분기 20개가 있다.

### 판단

네트워크 왕복은 줄었지만 상품 10,000개 조회·정렬과 좋아요 skip scan은 남았다.<br>
이 시점에는 `likes(product_id)` 인덱스가 없어 `IN + GROUP BY`보다 `UNION ALL`이 빨랐다. 인덱스 추가 후의 재검토는 7절에 정리했다.<br>
100 VU에서는 HTTP 500이 실측 6회 발생했다. 로그에서 Hikari 연결 40개가 모두 사용 중인 상태로 약 3초 후 연결 획득에 실패한 것을 확인했다.

## 5. 2단계: 좋아요 product_id 단일 인덱스

`UNION ALL`을 유지하고 `likes(product_id)` 인덱스를 추가했다. 기존 `(user_id, product_id)` 유니크 인덱스는 유지했다.

```sql
ALTER TABLE loopers.likes
    ADD INDEX idx_likes_product_id (product_id);
```

### 요청당 SQL 수와 좋아요 조회 비용

| 조회 | 적용 전 호출 수 | 적용 후 호출 수 | 적용 전 요청당 평균 DB 시간 | 적용 후 요청당 평균 DB 시간 |
| --- | ---: | ---: | ---: | ---: |
| 상품 목록 | 1,003 | 1,003 | 46.204 ms | 44.846 ms |
| 브랜드 일괄 조회 | 1,003 | 1,003 | 0.134 ms | 0.125 ms |
| 좋아요 UNION ALL | 1,003 | 1,003 | 77.575 ms | 0.326 ms |

요청당 SQL 3회·내부 COUNT 20회는 같고, 상품별 좋아요 검색 비용이 줄었다.

### EXPLAIN

| 조회·테이블 | 단계 | 접근 방식 | 사용 key | 예상 rows·filtered | Extra |
| --- | --- | --- | --- | ---: | --- |
| 좋아요 UNION의 각 분기 `l` | 적용 전 | `range` · skip scan | `uk_like_user_product` | 분기당 504,460·100% | `Using where; Using index for skip scan` |
| 좋아요 UNION의 각 분기 `l` | 적용 후 | `ref` | `idx_likes_product_id` | 분기별 1~10·100% | `Using index` |

20개 분기 모두 새 인덱스를 선택했다. 좋아요가 없는 상품의 예상 rows는 1이었지만 실제 COUNT 값은 `0`이다.

### EXPLAIN ANALYZE

첫 분기는 `product_id=5406` 기준이다.

| 연산자 | 실제 rows (전후) | loops (전후) | 적용 전 실제 시간 | 적용 후 실제 시간 |
| --- | ---: | ---: | ---: | ---: |
| 첫 분기 인덱스 검색 | 5 | 1 | 0.158–5.45 ms | 0.00354–0.004 ms |
| 첫 분기 COUNT | 1 | 1 | 5.45–5.45 ms | 0.00596–0.006 ms |
| 전체 UNION 결과 연결 | 20 | 1 | 5.45–72 ms | 0.00729–0.0477 ms |

각 분기의 접근 경로가 covering index skip scan에서 covering index lookup으로 바뀌었다.<br>
상품별 좋아요 0~10개와 COUNT 결과 한 행, 전체 UNION 결과 20행은 유지됐다.

### 판단

좋아요 검색 비용과 HTTP 지연이 줄었고 100 VU에서도 실패가 없었다.<br>
상품·브랜드의 접근 경로는 그대로여서, 상품 10,000개 조회 후 정렬하는 비용이 다음 개선 대상이었다.

## 6. 3단계: 상품 인덱스 (가격 단일 비교 후 복합 채택)

좋아요 인덱스·`UNION ALL`·seed·조회 SQL은 유지하고, **상품 인덱스 추가 전 → 가격 단일 → 단일 제거 후 복합** 순서로 비교했다.

### 비교안: price 단일 인덱스

```sql
ALTER TABLE loopers.products ADD INDEX idx_products_price (price);
```

가격순으로 읽으며 브랜드·삭제 조건을 확인했다. 정렬은 없어졌고, 상품 20개를 찾기까지 스캔 노드가 1,230행을 반환했다.

### 채택: 브랜드·삭제·가격 복합 인덱스

```sql
ALTER TABLE loopers.products DROP INDEX idx_products_price;
ALTER TABLE loopers.products
    ADD INDEX idx_products_brand_deleted_price (brand_id, deleted_at, price);
```

브랜드·삭제 조건 범위를 가격·ID 순으로 읽어 정렬 없이 20행에서 종료했다.<br>
가격 단일 인덱스는 제거하고 복합 인덱스를 최종 유지했다.

### 요청당 SQL 수와 DB 시간

아래 '상품 인덱스 추가 전'은 2단계 상태에서 별도로 수집한 값이다.

| 상태 | SQL/요청 | 상품 목록 평균 DB 시간 | 브랜드 평균 DB 시간 | 좋아요 평균 DB 시간 |
| --- | ---: | ---: | ---: | ---: |
| 상품 인덱스 추가 전 | 3 | 36.204 ms | 0.130 ms | 0.329 ms |
| 비교안: price 단일 | 3 | 1.473 ms | 0.067 ms | 0.359 ms |
| 채택: 복합 | 3 | 0.541 ms | 0.085 ms | 0.427 ms |

모든 상태에서 요청당 SQL 3회·내부 COUNT 20회는 유지됐다.

### EXPLAIN

상품 테이블의 접근 경로를 비교했다.

| 상태 | 접근 방식 | 사용 key | 예상 rows·filtered | Extra |
| --- | --- | --- | ---: | --- |
| 상품 인덱스 추가 전 | `ref` | `FKa3a4mpsfdf4d2y6r8ra3sc8mv` | 17,440·10% | `Using where` |
| 비교안: price 단일 | `index` | `idx_products_price` | 1,139·0.18% | `Using where` |
| 채택: 복합 | `range` | `idx_products_brand_deleted_price` | 18,758·100% | `Using index condition` |

가격 단일은 인덱스 순서대로 읽은 뒤 브랜드·삭제 조건을 필터링했고, 복합은 해당 조건의 범위를 직접 읽었다.<br>
추가 전의 `Using filesort`는 단일·복합 적용 후 사라졌다. 복합의 예상 18,758행은 조건 범위의 추정치이며 LIMIT까지 읽은 행 수와 구분한다.

### EXPLAIN ANALYZE

| 상태 | 스캔 노드 실제 rows | loops | 스캔 실제 시간 | LIMIT 실제 rows | LIMIT 실제 시간 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 상품 인덱스 추가 전 | 10,000 | 1 | 0.615–30.7 ms | 20 | 31.6–31.6 ms |
| 비교안: price 단일 | 1,230 | 1 | 0.162–1.21 ms | 20 | 0.308–1.25 ms |
| 채택: 복합 | 20 | 1 | 0.319–0.321 ms | 20 | 0.319–0.322 ms |

별도 정렬 없이 읽는 범위가 줄었다. 브랜드는 PK 1행 조회, 좋아요는 상품별 covering index lookup으로 유지됐다.

### 판단과 FK 인덱스 보존

이번 요청에서는 복합 인덱스가 조회 범위와 평균 지연을 더 줄였다.<br>
1 VU의 p99.9는 단일 15.642 ms·복합 16.209 ms로 소폭 높았지만, 100 VU에서는 단일 222.852 ms·복합 190.798 ms였다.

복합 인덱스 추가 시 자동 FK 인덱스가 대체돼 기존 `brand_id` 인덱스를 복원하고 JPA에도 명시했다.<br>
기존 인덱스가 없는 테스트 스키마에서 실패한 동시성 검사 2개도 복원 후 통과했다. 상세 스키마와 복원 SQL은 측정 원본에 남겼다.

## 7. 4단계: 좋아요 조회 IN + GROUP BY 재검토

2단계에서 `likes(product_id)` 인덱스를 추가하면서 1단계에서 `UNION ALL`을 고른 전제가 바뀌었다.<br>
3단계 상태(같은 seed·인덱스)에서 좋아요 조회만 `IN + GROUP BY`로 바꿔 다시 비교했다.

```sql
SELECT l.product_id, COUNT(l.id) AS like_count
FROM likes l
WHERE l.product_id IN (:productIds)
GROUP BY l.product_id;
```

좋아요가 없는 상품은 집계 행이 없으므로 애플리케이션에서 `0`으로 채운다. 요청당 SQL은 3회로 같다.

### 인덱스 유무에 따른 실행계획

인덱스가 없는 상태는 인덱스를 삭제하지 않고 `IGNORE INDEX (idx_likes_product_id)`로 재현했다. 시간은 EXPLAIN ANALYZE 3회의 전체 실행 시간 범위다.

| `likes(product_id)` | 방식 | 접근 경로 | 실제 읽은 rows | 전체 실행 시간 |
| --- | --- | --- | ---: | ---: |
| 없음 | UNION ALL | 분기마다 `uk_like_user_product` skip scan (20회) | 분기별 0~10 | 122–183 ms |
| 없음 | IN + GROUP BY | `uk_like_user_product` 전체 covering index scan + 임시 테이블 집계 | 5,194,998 | 516–557 ms |
| 있음 | UNION ALL | 분기마다 `idx_likes_product_id` covering lookup (20회) | 분기별 0~10 | 0.085–0.098 ms |
| 있음 | IN + GROUP BY | `idx_likes_product_id` covering range scan 1회 + 순서 그대로 집계 | 103 | 0.045–0.065 ms |

인덱스가 없으면 `product_id`가 유니크 인덱스의 두 번째 열이라 `IN` 목록 전체를 범위로 잡지 못하고, 좋아요 519만 행을 모두 읽은 뒤 임시 테이블로 집계했다.<br>
`UNION ALL`은 분기마다 skip scan으로 해당 상품만 찾을 수 있어 1단계 시점에는 더 나은 선택이었다.<br>
인덱스가 생기면 `IN + GROUP BY`는 한 번의 범위 스캔에서 인덱스 순서대로 집계해 임시 테이블과 분기 20개의 반복 실행이 사라진다.

### 교차 측정

앱을 방식별로 띄워 **UNION ALL → IN → IN → UNION ALL** 순서로 같은 세션에서 측정했다. 측정 방법은 1절과 같다.<br>
다른 로컬 프로세스가 관리 포트 8081을 사용 중이어서 관리 포트만 18082로 지정했다.

| 방식·회차 | 좋아요 평균 DB 시간 | 1 VU 평균 | 10 VU 평균 | 100 VU 평균 | 100 VU p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| UNION ALL 1회차 | 0.356 ms | 4.507 ms | 8.170 ms | 61.307 ms | 154.964 ms |
| IN + GROUP BY 1회차 | 0.205 ms | 4.310 ms | 7.128 ms | 60.431 ms | 116.059 ms |
| IN + GROUP BY 2회차 | 0.202 ms | 4.240 ms | 7.028 ms | 54.459 ms | 124.141 ms |
| UNION ALL 2회차 | 0.375 ms | 4.623 ms | 7.945 ms | 66.885 ms | 133.399 ms |

모든 회차에서 HTTP 실패율 0%·응답 검증 100%였다. 상품·브랜드 평균 DB 시간은 두 방식 모두 약 0.48–0.51 ms·0.07 ms로 같았다.<br>
좋아요 조회의 1,003요청 누적 실제 읽은 행은 두 방식 모두 103,309행이며, 반환 행은 UNION ALL 20,060행·IN 18,054행이다.

### 판단

좋아요 평균 DB 시간은 두 회차 모두 약 0.36 ms → 0.20 ms로 줄어 **`IN + GROUP BY`를 채택했다.**<br>
SQL이 페이지 크기와 관계없이 한 형태로 고정되고, 상품 ID를 하나의 목록 파라미터로 바인딩하는 점도 유지보수에 유리하다.<br>
HTTP 지연도 같은 VU에서 IN이 대체로 낮았지만, 요청당 차이가 0.15 ms 수준이라 회차 간 변동보다 작을 수 있어 HTTP 개선 근거로 쓰지 않는다.

이 선택은 `likes(product_id)` 인덱스가 있다는 전제에서만 유효하다. 인덱스를 바꾸면 좋아요 조회 방식도 함께 다시 측정한다.

측정 뒤 같은 쿼리를 Native SQL에서 JPQL로 옮겼다. 실행되는 SQL은 별칭만 다르고, 실행계획도 같은 `idx_likes_product_id` 범위 스캔이다.

## 8. 최종 검증

4단계 반영 후 `./gradlew :apps:commerce-api:check` 결과는 테스트 268개·실패/오류/skip 0개, Checkstyle·ArchUnit 통과다.<br>
UNION 분기 하나짜리 단위 테스트를 제거해 3단계 기록의 269개에서 1개 줄었다.<br>
정렬·페이지·삭제 조건과 정상 응답의 필드 값이 유지됨을 확인했다.

100 VU에서 p99·실패율·응답 검증은 목표를 충족했으며, **평균 54.459~60.431 ms를 20 ms 이하로 줄이는 목표가 남아 있다.**<br>
남은 지연은 SQL 실행이 아니라 커넥션 대기와 점유 시간에서 생긴다(2절). `likes_desc`의 전체 COUNT 정렬도 측정 대상 밖의 병목으로 남아 있다(3절).
