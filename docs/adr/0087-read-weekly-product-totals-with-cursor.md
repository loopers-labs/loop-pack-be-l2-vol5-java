# ADR 0087: 상품별 주간 점수는 집계 Cursor로 읽고 커밋한 상품 수에서 재개한다

Status: Superseded  
Superseded by: [ADR 0088](0088-page-weekly-product-totals-by-indexed-key.md) — 집계 Paging과 재시작 키  
Extends: [ADR 0079](0079-identify-weekly-snapshot-by-explicit-revision.md)  
Related: [실험 5](../week9/experiments.md#실험-5-상품별-합계를-읽는-세-가지-방식), [9주차 설계](../week9/design.md#점수-계산-calculateweeklyscores)

## Context

한 주의 상품별 구매 수량 합을 계산한다. 같은 Snapshot의 상품 하나가 Chunk item 하나이며, 실패한 실행은 같은 JobInstance에서 커밋된 상품 다음부터 이어 간다. 상품 ID만 먼저 읽으면 상품 수만큼 합계 SQL이 추가된다. 상품별 수량 합을 DB에서 만들어 읽는 방식도 같은 입력과 순서, 결과를 유지할 수 있다.

## Decision

- 고정 입력에 `GROUP BY product_id`와 `ORDER BY product_id`를 적용하고 `JdbcCursorItemReader<ProductScore>`로 읽는다. DB가 상품별 합계를 계산하고 Reader가 각 행을 검증된 점수로 매핑한다.
- commerce-batch의 Connector/J에 `useCursorFetch=true`를 설정한다. Cursor의 fetch size와 chunk 크기는 모두 `commerce.ranking.weekly.chunk-size`로 설정한다. 기본은 100이다.
- 점수는 Chunk의 절대값 UPSERT로 저장하고, 같은 로컬 쓰기 TX에서 구현 이름 `snapshotProductReader`의 `read.count`를 남긴다. Cursor는 별도 읽기 연결에서 이미 고정된 입력을 읽는다.
- 같은 입력·상품 ID 순서를 기준으로 커밋한 상품 수만큼 건너뛴다. Paging Reader가 남긴 `read.count`도 같은 의미이므로 기존 JobInstance와 Step/Reader 이름을 유지한다.

## Alternatives

| 방식 | 판단 |
| --- | --- |
| 상품 ID keyset + 상품별 합계 조회 | 정렬 키에서 다음 페이지를 찾지만 판매 상품 1만 개에서는 합계 SQL을 1만 번 호출했다 |
| 상품별 합계 keyset Paging | 추가 합계 조회를 없앴으나 측정한 실행 계획에서는 뒤쪽 페이지도 Snapshot 전체를 읽은 뒤 상품 범위를 필터링했다 |
| 집계 Cursor | 한 SELECT의 결과를 가져와 동일한 Chunk에 저장한다. 정상 완료와 9,000개 커밋 뒤 실패·복구의 합이 두 데이터 크기 모두 가장 짧았다 |
| 한 문장으로 집계·저장 | 실험 3에서 빠른 비교값이었다. 커밋 직전 실패하면 전체 계산을 반복하므로 상품 단위의 재시작 위치는 남지 않는다 |

실험 5에서 상품 1만 개의 구매 10만·20만 행을 비교했다. 세 방식 모두 같은 쓰기와 Chunk 크기였다. 정상 실행 중앙값은 집계 Cursor 약 0.91초·0.90초, 집계 Paging 약 4.99초·9.35초였다.

## Consequences

- Cursor가 열린 동안 DB 풀에서 읽기 연결 하나를 추가로 유지한다. 재시작하면 다시 집계 쿼리를 실행하고 커밋한 행 수만큼 이동한다.
- 상품 순서와 합계의 의미는 Snapshot 내 구매 사실과 상품 ID에 고정된다. 집계 기준이나 순서를 바꾸는 경우 기존 실패 실행의 checkpoint를 이어받을 수 있는지 별도로 확인해야 한다.
- 측정은 로컬 DB와 상품 1만 개까지다. 상품 수가 더 늘 때의 서버 메모리, 앱 메모리 및 커넥션 점유 비용은 별도 측정이 필요하다.

## 검증

- `RankingJobRestartTest`에서 Cursor로 시작하거나 기존 Paging의 영속 `read.count`에서 시작하는 경우 모두 상품 7·8의 commit에 이어 같은 JobInstance로 상품 9만 저장했다. 점수와 checkpoint의 롤백, 기대 순위·checksum도 확인했다.
- `ReaderSqlExperimentTest`에서 실제 집계 SELECT 한 번과 MySQL 서버 fetch를 확인했다. 기존 랭킹 API와 주문 확정 → Kafka → 구매 활동 → Batch → 공개 API 회귀도 통과했다.
