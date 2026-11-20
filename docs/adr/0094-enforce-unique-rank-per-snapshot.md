# ADR 0094: 같은 snapshot 안의 순위는 DB가 UNIQUE로 막는다

Status: Accepted
Extends: [ADR 0082](0082-publish-verified-snapshot-atomically-and-converge.md) (완성 랭킹 저장에 순위 유일성을 더한다)
Related: [ADR 0080](0080-batch-owns-weekly-ranking-tables-by-sql-schema.md), [9주차 설계 5·6절](../week9/design.md)

## Context

완성 랭킹(`ranking_entry`)은 공개 단계에서 stage 점수에 `ROW_NUMBER()`로 순위를 매겨 한 문장으로 저장한다. 이 문장만 쓰면 순위가 겹치지 않지만, 순위 열이 일반 인덱스라 DB는 중복을 막지 않는다. SQL을 바꾸다 실수하면 같은 순위의 상품 둘이 그대로 공개될 수 있다.

## Decision

순위 인덱스를 `UNIQUE KEY uk_ranking_entry_rank (snapshot_id, ranking)`로 둔다. 같은 snapshot에 같은 순위가 들어가면 저장이 실패하고 공개 TX가 rollback된다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| `ROW_NUMBER()` SQL만 믿음 | 정상 경로에서는 맞지만, 규칙을 지키는 곳이 SQL 하나뿐이다 |

## Consequences

- 인덱스가 이미 있던 자리라 읽기 비용은 그대로다.
- `CREATE TABLE IF NOT EXISTS`라 이미 만들어진 DB에는 반영되지 않는다.

## 검증

`WeeklySnapshotFreezeTest.rejectsDuplicateRanking`: 같은 snapshot에 순위 1을 두 번 넣으면 `DuplicateKeyException`.
