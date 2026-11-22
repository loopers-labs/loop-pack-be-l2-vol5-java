# ADR 0086: 늦은 구매 감지는 commerce-batch의 실행 모드(ApplicationRunner)로 판단하고 같은 프로세스에서 weeklyRanking을 실행한다

Status: Accepted
Related: [ADR 0079](0079-identify-weekly-snapshot-by-explicit-revision.md) (감지 정책), [ADR 0083](0083-abandon-wrong-snapshot-and-recalculate-next-revision.md), [9주차 설계 4절](../week9/design.md), [구현 기록](../week9/implementation-log.md)

## Context

ADR 0079는 늦은 구매 감지의 정책을 정했다.

- 매시 실행한다.
- 현재 UTC 주의 바로 앞 주만 본다.
- 원본 건수와 마지막 snapshot 건수를 비교한다.
- 최근 30분 동안 새 행이 없을 때만 다음 revision을 실행한다.
- 실패한 revision은 재시작한다.

남은 것은 이 판단을 어디서 하고 `weeklyRanking`을 어떻게 실행할지다.

commerce-batch는 job 하나를 실행하고 끝나는 앱이다. commerce-api는 batch를 실행할 수 없다. Batch job의 Step 안에서 다른 job을 실행하면, 바깥 Step 트랜잭션 안에서 JobRepository가 새 실행을 만들게 된다.

## Decision

- commerce-batch에 감지 실행 모드를 둔다.
  - 켜는 법: `--spring.batch.job.name=weeklyRanking --spring.batch.job.enabled=false --commerce.ranking.late-check.enabled=true`
  - 바깥 스케줄러가 매시 이 모드로 실행한다.
- 판단은 `LateArrivalDetector`가 한다. `Clock`을 받는 일반 클래스다.

| 마지막 revision | 원본 | 결정 |
| --- | --- | --- |
| 없음 | — | 아무것도 안 함(r1은 정기 실행) |
| FROZEN | — | 같은 snapshotId로 재시작 |
| ABANDONED | — | 아무것도 안 함(운영자 처리) |
| COMPLETED | 건수 같음 | 아무것도 안 함 |
| COMPLETED | 건수 다름, 최근 30분 안에 저장된 행 있음 | 다음 확인으로 미룸 |
| COMPLETED | 건수 다름, 조용함 | 다음 revision |

- 실행은 `LateArrivalRevisionRunner`(ApplicationRunner)가 맡는다.
  - 판단대로 같은 프로세스에서 `JobLauncher`로 `weeklyRanking`을 실행한다.
  - 결과를 `JobExecutionEvent`로 알려 프로세스 종료 코드에 반영한다.
- revision 순서·같은 기간 직렬 실행·동시 실행 거부는 job이 이미 지킨다(ADR 0081·0082). 감지기는 따로 잠그지 않는다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 판단만 하는 Batch job + 스케줄러가 결과를 읽어 다시 실행 | 두 단계가 되고, 판단 결과를 넘기는 통로가 따로 필요하다 |
| 판단 job의 Step 안에서 weeklyRanking 실행 | Step 트랜잭션 안에서 다른 job의 실행 기록을 만들게 된다 |
| commerce-api의 `@Scheduled`에서 판단 | api가 batch를 실행할 통로가 없다 |
| 원본 저장마다 즉시 판단 | 나눠 들어오는 늦은 구매마다 revision이 생긴다(ADR 0079에서 제외) |

## Consequences

- 매시 프로세스를 한 번 띄운다. 할 일이 없으면 판단만 하고 끝난다.
- 판단은 원본 건수와 `MAX(created_at)`을 그 주의 `confirmed_at` 범위로 조회한다. 원본에 `confirmed_at` 인덱스가 없어(ADR 0085) 전체를 훑는다.
- 스케줄러 설정은 배포 환경의 일이라 이 저장소에 두지 않는다.
- 감지 모드를 켜는 설정 조합(`late-check.enabled`와 `job.enabled=false`)으로 실제 프로세스를 띄워 보지는 않았다.

## 검증

`LateArrivalDetectorTest`(4개)로 확인했다. 시각은 고정 Clock으로 줬다.

- 2027-05-12 10:00 UTC의 대상 주는 2027-05-03이다. snapshot이 없으면 아무것도 하지 않는다.
- FROZEN이면 같은 snapshotId로 재시작하고, ABANDONED면 아무것도 하지 않는다.
- r1 공개 뒤 원본이 그대로면 아무것도 하지 않는다.
- 09:50에 저장된 늦은 구매는 10:00에는 미루고, 10:30에는 r2를 고른다.
- 실행기가 r2로 `weeklyRanking`을 실행해 COMPLETED·pointer r2가 된다. 다음 확인은 할 일이 없다.
