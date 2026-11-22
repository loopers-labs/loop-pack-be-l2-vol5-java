# ADR 0092: 멈춘 실행은 VERSION을 올리는 정리 SQL로 FAILED 표시한다

Status: Accepted
Extends: [ADR 0082](0082-publish-verified-snapshot-atomically-and-converge.md) (공개 commit 뒤 기록 실패 절차의 "FAILED 표시" 단계를 구체화한다)
Related: [9주차 설계 6절](../week9/design.md)

## Context

배포·OOM·서버 재시작으로 commerce-batch 프로세스가 죽으면 Spring Batch가 상태를 `FAILED`로 쓰지 못한다. 실행 기록이 `STARTED`로 남고, 같은 parameter의 재실행은 `JobExecutionAlreadyRunningException`으로 거부된다. Spring Batch 5.2에는 이 상태를 정리하는 기능(Batch 6의 `JobOperator#recover`)이 없어, 운영자가 metadata를 직접 고쳐야 한다.

ADR 0082는 "실행이 정말 끝났는지 먼저 확인한다"를 첫 단계로 두었다. 그런데 그 확인은 사람이 한다. 판단이 틀려 아직 살아 있는 실행을 `FAILED`로 바꾸면, 재시작한 실행과 옛 실행이 같은 점수를 겹쳐 쓸 수 있다.

## Decision

- 정리 SQL을 파일로 둔다: `apps/commerce-batch/src/main/resources/runbook/mark-execution-failed.sql`. Step·Job 실행 기록을 `FAILED`로 바꾸고 **`VERSION`을 1 올린다.** 업무 테이블은 건드리지 않는다.
- 끝난 줄 알았던 실행이 살아 있으면, 그 실행은 다음 chunk를 commit하면서 같은 TX에서 Step 기록을 저장한다. 이때 version이 맞지 않아 실패하고 그 chunk 전체가 rollback된다. Batch metadata와 업무 테이블이 같은 DataSource·트랜잭션 관리자를 쓰기 때문에 가능하다.
- 그렇게 멈춘 옛 실행은 기록을 `UNKNOWN`으로 남긴다. 그때는 정리 SQL을 한 번 더 실행한 뒤 재시작한다.
- 테스트는 이 SQL 파일을 그대로 실행한다. 문서와 테스트의 SQL이 어긋나지 않는다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 정리 절차만 문서로 두고 SQL은 그때그때 | 새벽 장애 때 사람이 SQL을 새로 쓰다 틀리기 쉽고, 안전장치가 없다 |
| 정리를 명령(코드)으로 만듦 | "정말 끝났나"는 코드도 판단할 수 없어 결국 사람이 확인한다. 명령·인자 검증·테스트가 더 필요하다 |
| 일정 시간 지난 STARTED는 자동으로 FAILED | 오래 걸리는 정상 실행을 끊을 수 있고 기준 시간을 정할 근거가 없다 |

## Consequences

- 운영자는 실행 ID를 찾아 SQL 파일을 실행한다. 프로세스 상태 확인은 여전히 사람이 한다.
- `VERSION`이 막는 것은 chunk 단위 commit이다. 옛 실행이 정리 SQL과 다음 commit 사이에 이미 commit한 chunk는 막지 못하지만, 그 chunk는 정상 commit이라 결과가 틀리지 않는다.
- 진짜 `kill -9`는 재현하지 않았다. 테스트는 상태를 `STARTED`로 되돌려 흉내 냈다.

## 검증

`StuckExecutionRecoveryTest`(2개).

- 상품 9 직전에 실패한 실행을 `STARTED`로 되돌리자 같은 parameter의 재실행이 `JobExecutionAlreadyRunningException`으로 거부됐다. 정리 SQL을 실행하자 같은 JobInstance의 새 실행이 상품 9만 읽고(readCount 1) 끝났고, 점수는 7=5, 8=1, 9=5였다.
- 상품 9를 쓰기 직전에 멈춰 있는 실행을 정리 SQL로 표시한 뒤 풀어 주자, 그 실행의 chunk commit이 "Attempt to update step execution id=… with wrong version" → "JobRepository failure forcing rollback"으로 실패했다. 상품 9 점수는 남지 않았고, 그 실행은 `UNKNOWN`으로 끝나 재실행이 `JobRestartException`으로 거부됐다. 정리 SQL을 한 번 더 실행하자 재시작이 끝났고 점수는 7=5, 8=1, 9=5였다.
