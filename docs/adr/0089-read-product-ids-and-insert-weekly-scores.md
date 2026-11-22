# ADR 0089: 상품 ID를 읽고 상품별 합계를 계산한 뒤 점수를 한 번 삽입한다

Status: Accepted  
Supersedes: [ADR 0088](0088-page-weekly-product-totals-by-indexed-key.md)  
Extended by: [ADR 0095](0095-reject-restart-with-different-chunk-size.md) — chunk 크기가 바뀐 재시작은 복원하지 않고 거부  
Related: [9주차 설계](../week9/design.md#점수-계산-calculateweeklyscores)

## Context

이번 과제에서 따르는 2-2번 흐름은 서로 다른 상품 ID를 읽고, Processor에서 상품별 전체 점수를 구하고, 그 점수를 Writer가 삽입하는 방식이다. 기존 구현은 상품별 합계까지 Reader가 조회하고 Writer는 절대값 UPSERT를 사용했다. 각 상품을 한 번만 처리하는 재시작 경계에 맞춰 두 책임과 쓰기 동작을 바꾼다.

## Decision

- `SnapshotProductPagingReader`는 고정 입력에서 `DISTINCT product_id`를 상품 ID 오름차순으로 읽는다. 다음 페이지는 `product_id > 마지막 키` 조건으로, 기존 상품별 인덱스를 사용한다.
- `SnapshotScoreProcessor`는 item의 ID와 `snapshotId`로 구매 수량을 합산해 `ProductScore`를 만든다.
- `ScoreStageWriter`는 JDBC Batch INSERT로 `(snapshot_id, product_id)`에 점수를 한 번 저장한다. 기본 키 충돌은 실패로 남겨 이미 커밋한 점수를 보호한다.
- 기존 Job·Step·Reader 이름과 상품 ID 순서, `read.count`·`start.after`·`page.size`를 유지한다. 점수 INSERT와 위치는 같은 Chunk 트랜잭션에서 커밋한다. 이미 커밋한 상품은 같은 JobInstance 재시작에서 다시 삽입하지 않고, 커밋 전에 실패한 Chunk는 다시 계산한다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 집계 Paging과 절대값 UPSERT | 상품별 SELECT를 줄이는 효과는 측정했다. 과제에서 따르는 Reader·Processor·Writer 흐름과 쓰기 방식을 적용하기 위해 대체한다. [실험 5·6](../week9/experiments.md#실험-5-상품별-합계를-읽는-세-가지-방식) |
| 구매 내역을 읽고 증가분 적용 | 구매별 적용 이력과 점수 증가를 한 트랜잭션에 저장해야 한다. 상품별 전체 점수를 한 item으로 다루는 구현과 처리 단위가 다르다. |

## Consequences

- 페이지 조회 외에 상품당 합계 SELECT가 한 번씩 실행된다. 기존 성능 실험은 UPSERT를 사용한 후보 비교 값이며 현재 Job의 처리 시간으로 인용하지 않는다.
- 점수 쓰기와 checkpoint의 원자성이 재시작의 전제다. 중복 키를 만났다면 해당 스냅샷의 stage와 실행 기록을 확인한다.
- 새 revision은 새 snapshotId로 점수를 저장한다. 같은 기간의 이전 공개 결과는 공개 단계에서 보호한다.

## 검증

- `ReaderSqlExperimentTest`에서 기존 인덱스를 사용한 고유 상품 ID 페이지, 상품당 한 번의 합계 SELECT, 점수 Batch INSERT를 확인했다.
- `RankingJobRestartTest`에서 7·8을 커밋한 뒤 같은 JobInstance로 재시작해 9만 삽입했다. 9 삽입 직후 실패 때는 점수와 체크포인트가 함께 롤백됐다.
- `WeeklySnapshotFreezeTest`에서 중복 INSERT는 실패하고 먼저 저장한 점수는 유지됐다.
