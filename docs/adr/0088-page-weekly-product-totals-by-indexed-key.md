# ADR 0088: 고정 입력의 상품별 점수는 기존 인덱스를 써서 키 기준으로 페이지 조회한다

Status: Superseded  
Superseded by: [ADR 0089](0089-read-product-ids-and-insert-weekly-scores.md)  
Supersedes: [ADR 0087](0087-read-weekly-product-totals-with-cursor.md)  
Related: [실험 6](../week9/experiments.md#실험-6-집계-paging도-상품-범위만-읽을-수-있을까), [9주차 설계](../week9/design.md#점수-계산-calculateweeklyscores)

## Context

주간 랭킹에서 상품 한 개의 전체 점수가 하나의 Chunk item이다. 고정 입력의 상품별 점수는 서로 독립적이며 상품 ID 정렬을 재시작에도 적용할 수 있다. 집계 Paging을 그대로 사용한 실험 5에서는 뒤쪽 페이지도 Snapshot 전체를 읽었다. 실험 6에서는 기존 인덱스를 사용하는 상품 범위 읽기로 개선했다. 같은 구매 10만 행에서 정상 실행 중앙값은 범위 Paging 1.234초, 집계 Cursor 1.041초였고, 9,000상품을 커밋한 뒤 재시작에 각각 256ms, 253ms가 들었다.

## Decision

- `SnapshotScorePagingReader`에서 상품별 구매 수량 합계를 상품 ID 오름차순으로 조회한다. 첫 페이지는 Snapshot 조건으로 조회한다. 이후 페이지는 같은 집계 SELECT의 WHERE에 `product_id > 마지막 키`를 넣는다. 기존 `idx_ranking_snapshot_input_product (snapshot_id, product_id, quantity)`를 사용하도록 지정한다.
- 점수는 여전히 Chunk에서 절대값 UPSERT한다. 점수와 `read.count`, `start.after`, `page.size`를 같은 Chunk 쓰기 TX에서 커밋한다. 페이지 크기와 Chunk 크기의 기본값은 100이다.
- 기존 Job·Step·Reader 이름을 이어 쓴다. 페이지 크기 정보가 없는 checkpoint는 고정 입력의 상품 ID 순서와 `read.count`에서 해당 페이지의 직전 키를 한 번 복원한다. Cursor가 사용하지 않아 남아 있던 과거 Paging 키도 현재 상품 수 기준으로 복원한다. 정상적인 Paging 재개는 저장한 키를 사용한다.
- 범위 Paging을 선택하면 별도 읽기 연결을 Step 동안 보유할 필요가 없고, 이전 위치부터 집계 결과를 다시 순회하지 않고 다음 키 조건으로 조회를 시작할 수 있다.

## Alternatives

| 방식 | 판단 |
| --- | --- |
| 집계 Cursor | 이번 로컬 비교의 정상 실행은 더 빨랐다. 실행 동안 별도 읽기 연결을 보유하며, 재시작하면 전체 집계 결과를 다시 열고 저장한 상품 수만큼 이동한다 |
| Spring Batch 기본 집계 Paging | 후속 쿼리가 집계를 서브쿼리로 감싼다. 실험에서 뒤쪽 페이지에서도 Snapshot 구매 행 100,000개를 읽었다 |
| 범위를 명시하되 인덱스 선택은 옵티마이저에 맡김 | SQL을 단순하게 유지할 수 있다. 실험에서는 PRIMARY를 고른 결과 여전히 100,000행을 읽었다 |
| 기존 인덱스를 지정한 범위 Paging | 상품 ID 9,000 이후 100개 상품을 반환하는 쿼리의 실제 인덱스 읽기가 1,001행이었다 |

## Consequences

- 페이지와 마지막 빈 페이지 확인에 각각 SELECT가 필요하다. 페이지마다 SQL 왕복 비용이 든다.
- 현재 SQL은 MySQL과 해당 보조 인덱스에 의존한다. 기존 DB에서도 그 인덱스가 있는지 배포 스키마를 확인한다.
- 이전 상태를 복원하는 조회에 한해서 OFFSET을 사용한다. 일반적인 페이지 조회·재시작은 상품 ID 키를 쓴다.
- 커넥션 반환 시점과 풀 대기는 구분한다. 동시 실행이 많은 조건에서 실제 연결 획득 지연은 별도 측정이 필요하다.

## 검증

- 커밋 직후 같은 풀의 사용 중 연결 증가를 샘플링한 실험 7에서 범위 Paging은 0개, Cursor는 1개였다. 실제 재시작의 첫 Paging SELECT에서 상품 키 조건 `product_id > 8`을 확인했다.
- `RankingJobRestartTest`에서 신규 Paging 실패·재시작과 이전 Cursor 실패 실행의 전환을 확인했다. 이전 Paging 키가 남은 경우와 키가 없는 경우 모두 같은 JobInstance에서 상품 9만 읽고 저장했다.
- `ReaderSqlExperimentTest`에서 실제 Job이 생성한 SELECT의 보조 인덱스 지정, 첫 페이지 이후의 마지막 상품 키 조건을 확인했다.
