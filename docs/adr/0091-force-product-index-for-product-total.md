# ADR 0091: 상품별 합계 조회에 상품 인덱스를 지정한다

Status: Accepted
Related: [ADR 0089](0089-read-product-ids-and-insert-weekly-scores.md), [실험 13·15](../week9/experiments.md), [9주차 설계 4절](../week9/design.md)

## Context

점수 계산 Step의 Processor는 상품마다 `SELECT SUM(quantity) … WHERE snapshot_id = ? AND product_id = ?`를 보낸다(ADR 0089). 실제 배치는 입력 고정 Step이 snapshot 입력을 대량으로 넣은 **바로 다음 Step**에서 이 조회를 시작한다.

실험 13에서 통계가 거의 빈 테이블에 넣은 직후(`n_rows` 8~20, 실제 5만 행) 계산하자, optimizer가 상품 인덱스 대신 PRIMARY로 snapshot 전체를 읽었다. 1천 상품 × 50행에서 검사한 행이 약 5,000만 행, Step이 약 9초였다. 통계를 갱신하면 같은 계산이 약 0.5초, 10만 행이었다. 이전 주 입력이 쌓인 테이블에서는 문제가 드러나지 않았지만, 배포 뒤 첫 실행처럼 통계가 비어 있는 경우는 생길 수 있다.

## Decision

상품별 합계 조회에 Reader와 같은 `idx_ranking_snapshot_input_product (snapshot_id, product_id, quantity)`를 `FORCE INDEX`로 지정한다. 이 인덱스는 조회에 필요한 열을 모두 담고 있어, 통계 상태와 관계없이 그 상품의 행만 읽는다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 입력 고정 뒤 `ANALYZE TABLE` | MySQL의 `ANALYZE TABLE`은 그때까지의 TX를 자동 commit한다. 입력 고정 TX 안에서 쓸 수 없어 Step이 하나 더 필요하고, 그 시간과 영향은 재지 않았다 |
| optimizer에 맡김 | 실험 13처럼 통계 상태에 따라 계획이 바뀐다 |

## Consequences

- SQL이 인덱스 이름에 묶인다. Reader도 같은 인덱스를 지정하므로(ADR 0088·0089), 이름을 바꾸면 두 곳을 함께 고친다. 인덱스는 batch가 소유한 스키마 파일에 있다(ADR 0080).
- 한 상품에 구매가 몰린 경우에도 그 상품의 행을 인덱스로 읽는다. 통계를 갱신한 상태와 시간 차이는 작았다(실험 15).

## 검증

실험 15(이번 주 5만 행, 조합마다 3회): 빈 테이블에 넣은 직후에도 1천 상품 × 50행이 0.50~0.57초, 검사한 행 100,009행으로 `ANALYZE` 뒤(0.47~0.53초)와 같았다. 대책 전에는 8.9~9.2초, 약 5,000만 행이었다. 통계를 갱신한 상태에서도 느려지지 않았다. 36번 모두 별도 집계와 점수가 같았다. `:apps:commerce-batch:check`와 `ReaderSqlExperimentTest`가 통과했다.
