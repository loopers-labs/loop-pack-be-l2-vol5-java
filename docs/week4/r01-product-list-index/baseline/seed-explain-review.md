# seed 실행 전 계획 검토

[계획](../plan.md) · [사전 상태](seed-preflight.txt) · [EXPLAIN SQL](seed-explain-before.sql) · [계획 원문](seed-explain-before.txt)

상태: 실행 계획 설명 뒤 **사용자 승인 완료, 적재·검증 성공**. 승인한 실행 결과는 [seed 실행 기록](seed-run.json)과
[전체 결과](../result.md)에 별도로 기록한다.
이 사전 검토의 원문을 수집할 때는 INSERT를 실행하지 않았다.
MySQL 8.0.46의 EXPLAIN과 EXPLAIN FORMAT=TREE만 실행했다. ANALYZE TABLE·EXPLAIN ANALYZE·설정 변경은 하지 않았다.

## 현재 상태

- 전용 DB: `week4_r01_index_benchmark`.
- 남은 InnoDB 트랜잭션 0개, brands/users/wallets/products/product_likes 모두 실제 COUNT 0건.
- 버퍼 풀 `134217728` bytes, 즉 원래 128MiB. 기존 상품 보조 인덱스 4개 유지, 새 인덱스 2개 미적용.
- 소스/JAR 기준 `98da9d2`: ApplicationRunner, 트랜잭션 제한 3,600초, 대량 INSERT 3개의 삽입 PK 오름차순.

## INSERT별 접근 경로

다음 삽입 건수는 seed 공식의 예상값이며 이번 검토에서 실제 적재한 결과가 아니다.

| 순서 | 대상·예상 삽입 건수 | EXPLAIN의 읽기·생성 경로 | 정렬 |
|---|---|---|---|
| 1 | brands 1,000개 | 10개 숫자 CTE를 세 번 조합하는 hash join, 예상 출력 1,000행 | 별도 정렬 없음 |
| 2 | users 1,000개 | brands의 idx_brands_deleted_created 커버링 인덱스 전체 스캔 | 별도 정렬 없음 |
| 3 | wallets 1,000개 | users PRIMARY 커버링 인덱스 전체 스캔 | 별도 정렬 없음 |
| 4 | products 1,000,000개 | 숫자 1,000개와 brands 1,000개를 hash join, 예상 출력 100만 행 | 삽입 상품 ID 오름차순 |
| 5 | product_likes 2,502,980개 | products의 idx_products_deleted_brand_like 전체 스캔, k=1..5 조합·like_count 필터 | 삽입 좋아요 ID 오름차순 |
| 6 | product_likes 95,000개 | 같은 상품 커버링 인덱스 전체 스캔 후 like_count=100 필터, k=6..100 조합 | 삽입 좋아요 ID 오름차순 |

products의 기존 좋아요순 인덱스인 idx_products_deleted_brand_like는 읽기에 필요한 id·brand_id·like_count를 제공한다.
전체 상품에 관계를 생성하므로
상품 인덱스 전체를 읽는 경로다. INSERT 대상 줄의 type=ALL을 읽기 테이블의 풀 스캔과 혼동하지 않는다.

대량 INSERT 3개에는 Using temporary / Using filesort와 TREE의 Sort가 나타난다.
이는 삽입할 PK 순서를 명시하기 위한 정렬 비용이다. filesort라는 표시만으로 실제 디스크 정렬 여부를 알 수 없다.
PK순 적재의 전체 시간 이득은 이 추정 계획만으로 확정하지 않는다.
좋아요는 두 INSERT 각각 PK순이며 두 INSERT를 합친 전역 PK 순서까지 연속인 구조는 아니다.

## 추정의 한계와 실행 조건

실제 products COUNT는 0이지만 이전 적재·롤백의 통계가 남아 EXPLAIN은 935,097행을 추정한다.
brands/users/wallets도 실제 0건과 달리 각각 1,000행의 통계가 남았다. 앞 단계의 예상 1,000·100만 행도
선행 INSERT가 실행된 결과가 아니라 이 통계와 숫자 생성식으로부터 나온 추정이다.
첫 좋아요 INSERT의 출력 추정은 약 156만 행, 두 번째는 약 93.5만 행으로 공식상의 실제 예정 건수와 다르다.
빈 테이블 상태에서 앞 INSERT가 후속 INSERT의 입력을 만들기 때문에, 지금의 계획이 각 적재 시점의 계획을 보장하지도 않는다.
cost는 밀리초가 아니며 실제 rows·실행 시간·INSERT의 인덱스 갱신 비용·전체 검증/커밋 시간은 이 결과로 측정하지 못한다.

승인받으면 SQL 6개를 기존 로더로 실행한다. 전체 적재와 관계·집계 검증 뒤 한 번 커밋하고, 실패 시 전체 롤백한다.
버퍼 풀과 다른 DB/서버 설정은 변경하지 않는다. 타임아웃이 늘어난 ID순 버전을 사용한다.
새 인덱스 적용과 JMeter는 이번 적재에 포함하지 않는다.
