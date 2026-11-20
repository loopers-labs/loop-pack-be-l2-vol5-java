# ADR 0079: 주간 랭킹 입력은 명시한 revision의 snapshot으로 식별하고, 늦은 구매는 직전 주만 자동으로 새 revision을 만든다

Status: Accepted
Extended by: [ADR 0081](0081-freeze-snapshot-once-in-revision-order.md) — revision을 하나씩 올리도록 강제하고 이미 있는 snapshot의 처리를 정함  
Extended by: [ADR 0087](0087-read-weekly-product-totals-with-cursor.md) — 같은 Snapshot의 상품별 점수를 읽고 영속 checkpoint에서 재개하는 방식
Related: [9주차 설계 2~4절](../week9/design.md), [구현 기록](../week9/implementation-log.md)

## Context

주간 랭킹은 한 기간의 구매 활동을 입력으로 계산한다. 구매 활동은 확정 시각(`confirmed_at`)으로 주에 소속되지만 DB 도착은 늦을 수 있다. 계산 도중이나 재시작 전에 늦은 구매가 들어오면, 원본을 다시 읽는 재시작은 한 결과 안에 서로 다른 입력을 섞는다.

Spring Batch는 job 이름과 식별 parameter가 같으면 같은 JobInstance로 보고 마지막 commit 위치에서 이어 간다. 식별 parameter가 기간 하나뿐이면 "같은 입력을 이어 하는 재시작"과 "늦은 구매를 넣은 재계산"을 구분할 수 없다.

정상 경로의 도착 지연은 수 분 안이다(Outbox relay 10초 주기, 발행 재시도 최대 60초 간격, consumer 저장 재시도 합계 5분). consumer 서버 중단, DB 오류가 재시도 5분을 넘겨 listener가 멈춘 경우(사람이 다시 시작할 때까지 Kafka에 남는다), 메시지 오류로 DLT에 간 뒤 고쳐서 재처리하는 경우처럼 장애로 늦어지는 경우는 길이에 상한이 없다.

과제는 늦은 구매를 원래 주의 새 revision으로 명시해 다시 계산하라고 하지만, 얼마나 지난 주까지 고칠지는 정하지 않았다. 이 랭킹은 메인의 "지난주 인기 상품" 노출용으로 본다.

## Decision

- 입력은 snapshotId `weekly-{yyyyMMdd}-r{revision}`로 식별한다. 식별 job parameter는 `periodStart`·`snapshotId`이며, 재시작은 같은 값, 재계산은 다음 revision이다. `run.id`·현재 시각은 붙이지 않는다.
- revision 번호는 배치 밖에서 정해 넘긴다. 배치는 받은 snapshotId로만 일한다.
- 정기 실행은 UTC 월요일 00:30에 직전 주 r1이다. 유예 30분은 정상 경로 지연만 흡수한다. 장애로 늦은 구매는 유예로 기다리지 않는다.
- 늦은 구매 감지는 매시 돌며, 현재 UTC 주의 바로 앞 주 하나만 대상으로 한다. 원본 건수와 마지막 snapshot 건수가 다르고, 그 기간에 최근 30분 동안 새로 저장된 행이 없으면 다음 revision을 실행한다. 마지막 revision의 실행이 실패 상태면 새 revision 대신 같은 parameter로 재시작한다.
- 그보다 오래된 주는 자동으로 다시 계산하지 않는다. 운영자가 다음 revision을 직접 실행할 수는 있다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 배치가 DB를 보고 다음 revision을 스스로 정함 | 실패한 r1을 재시작하려고 같은 명령을 다시 실행하면 r2를 만들어 재시작과 재계산이 섞인다 |
| 식별 parameter를 기간 하나로 두고 원본을 다시 읽음 | 재시작 사이 늦은 구매가 들어오면 앞 chunk와 뒤 chunk의 입력이 다르다 |
| 유예 시간을 길게(몇 시간~하루) 두고 r2를 두지 않음 | 장애 길이에 상한이 없어 어떤 유예도 모두 덮지 못하고, 그만큼 공개가 늦다 |
| 늦은 구매를 운영자가 확인해 r2 실행 | 장애 복구마다 사람이 실행해야 한다 |
| 저장마다 즉시 r(N+1) 실행 | 나눠 들어오는 늦은 구매마다 revision이 생긴다 |
| 최근 여러 주(예: 4주) 또는 기간 제한 없이 감지 | 메인 노출이 끝난 과거 순위가 나중에 바뀐다. 노출용 랭킹에서 얻는 것이 적다 |
| `created_at > 입력 고정 시점`으로 늦은 구매 감지 | `created_at`은 저장 전에 앱이 찍은 시각이라 입력 고정 뒤 commit됐지만 시각은 그 전인 행을 놓친다 |

## Consequences

- r1 공개부터 늦은 구매를 반영한 revision 공개까지 고객은 그 구매가 빠진 랭킹을 본다. API는 입력 고정 시점을 함께 응답한다.
- 감지 대상 주가 바뀐 뒤 도착한 구매는 과거 주 랭킹에 자동 반영되지 않는다.
- 혜택·정산처럼 순위가 확정돼야 하는 용도가 생기면 마감 시점을 다시 정해야 한다.
- 스케줄러 설정은 배포 환경의 일이라 이 저장소에 만들지 않는다.

## 검증

구현 전 결정이다. 지연 근거는 commerce-api의 relay·재시도 설정과 commerce-streamer의 consumer 재시도 설정을 읽어 확인했다. 동작 검증은 [구현 기록](../week9/implementation-log.md)을 따른다.
