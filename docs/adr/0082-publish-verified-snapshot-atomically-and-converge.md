# ADR 0082: 검증한 완성본만 한 TX로 공개하고, 같은 기간은 revision 하나씩 계산하며, 공개 재실행은 같은 결과로 수렴한다

Status: Accepted
Extends: [ADR 0081](0081-freeze-snapshot-once-in-revision-order.md) (입력 고정에 "이전 revision이 완성돼야 시작" 조건을 더한다)
Extended by: [ADR 0083](0083-abandon-wrong-snapshot-and-recalculate-next-revision.md) — 완성 조건 실패의 복구(`ABANDONED`)
Extended by: [ADR 0092](0092-clear-stuck-execution-with-version-bump.md) — 멈춘 실행의 FAILED 표시를 VERSION을 올리는 정리 SQL로
Extended by: [ADR 0094](0094-enforce-unique-rank-per-snapshot.md) — 완성 랭킹의 순위 UNIQUE
Related: [9주차 설계 6절](../week9/design.md), [구현 기록](../week9/implementation-log.md)

## Context

계산 Step은 chunk마다 stage에 점수를 commit한다. 과제가 공개 단계에 요구하는 것은 다음과 같다.

- API가 stage를 읽으면 중간 실패가 고객 결과를 바꾼다. 모든 점수·순위를 검증한 뒤 완성 표시와 공개 pointer를 원자적으로 바꿔야 한다.
- 공개 TX가 rollback되면 이전 pointer가 유지돼야 한다.
- 같은 snapshot의 공개를 다시 해도 결과가 늘거나 순위가 바뀌지 않아야 한다.
- 오래된 실행이 더 새 revision의 공개본을 덮으면 안 된다.
- 같은 기간의 동시 계산은 기본 범위에서 직렬로 실행한다.

공개 TX의 commit과 Batch 최종 상태 저장은 같은 순간에 일어나지 않는다. Spring Batch 5.2.2는 Step이 끝난 뒤 최종 상태 저장이 실패하면 그 Step을 `UNKNOWN`으로 둔다.

## Decision

**공개 Step** (`publishWeeklyRanking`, 기본 격리 수준의 한 TX):

1. snapshot이 `FROZEN`이면 완성 조건을 확인한다.
   - stage 행 수 = 상품 수
   - stage 점수 합 = 입력 quantity 합
   - 입력 요약(건수·상품 수·checksum) = 고정 당시 값
2. 순위(점수 내림차순, 동점이면 productId 오름차순, `ROW_NUMBER`)를 매겨 완성 랭킹을 저장한다.
3. 결과 checksum과 함께 snapshot을 `COMPLETED`로 표시한다.
4. 공개 pointer 행을 잠그고 판단한다.
   - pointer가 없거나 더 낮은 revision이면 바꾼다.
   - 같은 snapshot이면 그대로 둔다.
   - 더 높은 revision이면 바꾸지 않고 종료 코드 `NOT_PUBLISHED_OLDER_REVISION`으로 정상 종료한다.

**같은 기간의 직렬 실행**: 입력 고정은 그 기간에 `COMPLETED`가 아닌 revision이 있으면 실패한다.

**수렴**: snapshot이 이미 `COMPLETED`면 1~3을 하지 않는다. 저장된 결과 checksum을 확인한 뒤 4만 다시 판단한다.

**공개 commit 뒤 Batch 기록 실패**: 다음 순서로 처리한다.

1. 실행이 정말 끝났는지 확인한다. DB에 `STARTED`·`UNKNOWN`으로 남은 실행은 아직 돌고 있을 수도 있다. 그 실행을 띄운 commerce-batch 프로세스가 남아 있지 않은지, 같은 JobInstance의 다른 실행이 진행 중이지 않은지 본다.
2. 공개 상태(pointer, `COMPLETED`, 결과 checksum)를 확인한다.
3. 1·2를 확인한 뒤에만, 멈춘 실행을 지우지 않고 `FAILED`로 표시한다. 확인 없이 `FAILED`로 바꾸는 일반 규칙으로 쓰지 않는다.
4. 같은 parameter로 재시작한다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 완성 조건을 상품 수만 확인 | 점수가 잘못 더해져도 통과한다 |
| 상품마다 점수를 다시 계산해 비교 | 가장 확실하지만 계산을 한 번 더 하는 것과 같다. 합계 비교로 빠진 상품·잘못된 합계를 대부분 잡는다 |
| stage를 그대로 API가 읽고 완료 표시만 둠 | 계산 중·실패 결과가 같은 테이블에 섞인다. ADR 0080에서 테이블을 나눴다 |
| 기존 공개 결과를 지우고 새로 넣기를 두 TX로 | 사이에 빈 결과가 보인다 |
| 기간별 잠금 테이블(실행 중 표시)로 직렬화 | 앱이 죽으면 표시가 남아 해제 절차가 따로 필요하다. revision 순서 규칙에 조건 하나를 더하는 편이 단순하다 |
| 공개 순간만 직렬화(계산은 동시) | 과제의 "계산을 직렬로"와 다르다 |
| 오래된 revision이면 Job을 FAILED로 끝냄 | 알림은 확실하지만 재시작해도 영원히 실패한다. 계산은 정상이고 공개만 하지 않은 것이라 정상 종료 + 종료 코드로 구분한다 |
| 이미 COMPLETED여도 완성 랭킹을 다시 만들기 | 결과는 같지만 공개 중인 행을 다시 쓰게 된다. 확인만 하고 건드리지 않는다 |
| 공개 commit 뒤 기록 실패 시 공개를 되돌림 | 이미 고객이 보는 정상 결과를 metadata만 보고 지우게 된다 |
| 공개 상태만 확인하고 바로 FAILED로 표시 | 실제로 진행 중인 실행이면 재시작한 실행과 같은 작업이 겹친다 |
| 일정 시간 지난 STARTED는 자동으로 FAILED | 오래 걸리는 정상 실행을 끊을 수 있고, 기준 시간을 정할 근거가 없다 |

## Consequences

- 이전 revision이 계속 실패하면 같은 기간의 다음 revision을 시작할 수 없다. 실패를 숨기지 않는 대신 사람이 원인을 고쳐야 한다.
- 완성 조건이 실패하면 계산 Step은 이미 `COMPLETED`라 재시작이 공개만 다시 한다. stage가 틀린 경우의 복구 경로는 아직 정하지 않았다(설계 7절).
- 공개 commit 뒤 기록 실패는 자동으로 이어지지 않는다. Spring Batch가 재실행을 거부하므로 사람이 사실을 확인하고 표시해야 한다.
- 운영자가 프로세스 상태를 볼 수 있어야 한다. 확인 방법은 배포 환경에 따라 다르며 이 저장소에 도구를 두지 않는다.
- API는 pointer가 가리키는 snapshot의 `ranking_entry`만 읽어야 한다(6단계).

## 검증

`WeeklyRankingPublishTest`(6개)와 `RankingJobRestartTest`에서 확인했다.

- 새 revision의 계산이 실패하면 이전 공개본이 그대로이고, 재시작하면 새 revision으로 바뀐다.
- pointer 교체 직전 실패 → 완성 랭킹·완성 표시까지 rollback, 이전 공개본 유지, 재시작은 공개 Step만 실행해 공개한다.
- 점수 합이 다르면 공개하지 않는다.
- 더 새 revision이 공개돼 있으면 pointer를 바꾸지 않고 `NOT_PUBLISHED_OLDER_REVISION`으로 끝난다.
- 이전 revision이 미완성이면 다음 revision의 입력 고정이 실패하고, 이전 revision을 끝낸 뒤에는 실행된다.
- 공개 commit 뒤 공개 Step 최종 상태 저장을 실제로 실패시켰다. 그 결과는 다음과 같았다.
  - Job은 `UNKNOWN`, 공개 Step은 DB에 `STARTED`로 남았다.
  - pointer·`COMPLETED`·완성 랭킹은 남았다.
  - 같은 parameter의 재실행은 `JobExecutionAlreadyRunningException`으로 거부됐다.
  - 실행을 `FAILED`로 표시하고 재시작하자 공개 Step만 실행되어 COMPLETED가 됐고, pointer·랭킹·결과 checksum·완성 시각은 그대로였다.
  - 이 테스트는 실행이 이미 끝난 상태에서 확인했다. "아직 돌고 있는 실행"을 만들어 1단계를 확인하지는 않았다.
- 과제 6절 입력의 재시작 결과는 순위 7 → 9 → 8, 결과 checksum `745d50bb…3b0a67d2`(직접 계산값)였다. 별도 주에서 한 번에 실행한 결과와 같았다.
