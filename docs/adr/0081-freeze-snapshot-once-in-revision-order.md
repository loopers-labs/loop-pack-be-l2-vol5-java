# ADR 0081: 입력 고정은 revision 순서대로 한 번만 하고, 이미 있는 snapshot은 같을 때만 그대로 쓴다

Status: Accepted
Extends: [ADR 0079](0079-identify-weekly-snapshot-by-explicit-revision.md) (명시한 revision을 하나씩 올리도록 강제하고, 같은 snapshotId의 입력 고정이 다시 실행될 때의 처리를 더한다)
Extended by: [ADR 0082](0082-publish-verified-snapshot-atomically-and-converge.md) — 이전 revision이 완성돼야 다음 revision의 입력 고정을 시작
Related: [9주차 설계 6절](../week9/design.md), [구현 기록](../week9/implementation-log.md)

## Context

ADR 0079는 snapshotId `weekly-{yyyyMMdd}-r{revision}`을 배치 밖에서 정해 넘기기로 했다. 과제의 요구는 다음과 같다.

- 기간별 revision은 명시적으로 증가시킨다.
- 완성된 입력은 재시작할 때 다시 복사하거나 추가하지 않는다.
- 이미 존재하는 snapshotId의 기간·입력·revision이 다르면 덮어쓰지 않고 충돌로 처리한다.
- 입력 고정이 실패하면 반쪽 snapshot을 계산 대상으로 쓰지 않는다.

입력 고정 Step의 TX에는 원본 복사와 snapshot 정보 저장이 함께 들어 있다. 이 TX가 commit된 뒤에도 Spring Batch가 그 Step의 완료 상태를 저장하는 일이 남는다. 이 저장이 실패하면 Step은 완료로 기록되지 않는다. 그러면 같은 JobInstance를 재시작할 때 입력 고정 Step이 다시 실행되는데, snapshot은 이미 있다.

## Decision

입력 고정 Step은 한 TX 안에서 다음 순서로 판단한다.

| 상황 | 처리 |
| --- | --- |
| 같은 snapshotId의 snapshot 정보가 있고, 저장된 기간·revision이 같고, 저장된 snapshot 입력으로 다시 계산한 건수·상품 수·checksum이 snapshot 정보와 같음 | 다시 복사하지 않고 그대로 쓴다 |
| 같은 snapshotId의 snapshot 정보가 있지만 위 중 하나라도 다름 | 충돌로 실패한다. 아무것도 쓰지 않는다 |
| 처음 보는 snapshotId이고 revision이 그 기간의 마지막 revision + 1(없으면 1) | 복사하고 건수·상품 수·checksum과 snapshot 정보를 저장한다 |
| 처음 보는 snapshotId인데 revision을 건너뜀 | 실패한다 |

- snapshotId의 날짜와 `periodStart`가 다르거나 형식이 틀리면 job parameter 검사에서 거부한다.
- 이미 있는 snapshot이 같은지 판단할 때 원본과는 비교하지 않는다. 원본은 늦은 구매로 늘 수 있고, 그 차이는 다음 revision의 몫이다.
- 같은 기간에 r2와 r3을 동시에 만드는 경합은 `UNIQUE(period_start, revision)`과 "마지막 + 1" 검사가 막는다. 같은 snapshotId의 동시 실행은 Spring Batch가 같은 JobInstance의 동시 실행으로 거부한다. 서로 다른 revision 계산의 전체 직렬화는 공개 단계에서 정한다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 이미 있는 snapshotId면 항상 실패 | 처음 합의한 안이다. 입력 고정 TX commit 뒤 Step 완료 기록만 실패한 경우, 그 JobInstance는 다시 실행해도 매번 실패해 이어 갈 수 없다 |
| 이미 있으면 지우고 다시 복사 | 늦은 구매가 들어가 같은 snapshotId의 입력이 바뀐다. 과제의 "다시 복사하지 않음"에 어긋난다 |
| revision 건너뛰기 허용(r1 다음 r3) | 과제가 건너뛰기를 금지하지는 않는다. 번호에 빈칸이 생겨 기록을 읽을 때 빠진 revision을 찾게 되고, 감지기도 +1로 만들므로 허용해서 얻는 것이 없다 |
| 같은지 판단할 때 원본도 비교 | 재시작 사이에 정상적으로 도착한 늦은 구매 때문에 충돌이 된다 |

## Consequences

- 운영자가 번호를 잘못 넣으면 job이 실패하고 원인이 메시지로 남는다. 바른 번호로 새로 실행해야 한다.
- 실패한 revision의 입력 고정 TX가 rollback됐다면 snapshot이 없으므로, 같은 parameter로 다시 실행할 때 다시 복사한다. 그 사이 늦은 구매가 왔다면 들어간다. 계산에 아직 쓰지 않은 입력이라 섞이지 않는다.
- 저장된 snapshot 입력이나 snapshot 정보가 손으로 바뀌었다면 충돌로 실패한다. 실패를 고치는 방법은 다음 revision이다.

## 검증

`WeeklySnapshotFreezeTest`에서 확인했다.

- r1 없이 r2를 실행하면 실패하고 snapshot이 남지 않는다.
- r1 뒤에 늦은 구매가 오면 r2 입력에만 들어가고 r1 입력은 그대로다.
- 이미 있는 snapshot의 요약이 맞으면 다시 복사하지 않고 그 입력으로 계산한다. 요약이 다르면 실패하고 입력을 복사하지 않는다.

실제 "TX commit 뒤 Step 완료 기록 실패"는 만들지 않았다. snapshot 정보와 입력을 미리 넣어 같은 상태를 흉내 냈다. 결과는 [구현 기록](../week9/implementation-log.md)을 따른다.
