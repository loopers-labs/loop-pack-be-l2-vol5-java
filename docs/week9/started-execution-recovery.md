# weeklyRanking 실패 복구

## 같은 revision으로 이어갈 때와 새로 계산할 때

`FAILED`로 끝난 실행은 같은 `periodStart`와 `snapshotId`로 재시작할 수 있다. 예를 들어 `periodStart=2026-08-17`에서 `snapshotId=weekly-20260817-r1`을 다시 주면 r1을 이어가고, 아직 사용하지 않은 `weekly-20260817-r2`를 주면 같은 주의 새 계산을 시작한다.

| 상황 | 실행 선택 | 다시 처리하는 범위 |
| --- | --- | --- |
| 구매 수량 오류로 입력 고정 단계가 롤백되어 고정 입력이 남지 않았다 | 원장을 정정하고 같은 revision으로 재시작 | 입력 고정부터 다시 실행해 정정된 수량을 읽는다. |
| 상품 7·8의 점수는 정상 커밋됐고 상품 9에서 계산이 일시적으로 실패했다 | 같은 revision으로 재시작 | 고정 입력과 저장된 점수를 유지하고 상품 9부터 계산한다. |
| 점수 계산이 끝났고 공개본 교체 직전에 일시적으로 실패했다 | 같은 revision으로 재시작 | 공개 단계만 다시 실행한다. 그동안 이전 공개본이 유지된다. |
| 입력 고정 후 늦게 들어온 구매까지 이번 주 순위에 반영해야 한다 | 같은 주의 새 revision 실행 | 구매 원장에서 입력을 새로 고정하고 전체 순위를 계산한다. |
| 계산식 오류가 이미 커밋된 점수에도 영향을 줬고 당시 구매 내역으로 다시 계산해야 한다 | 새 revision에 `sourceSnapshotId`로 이전 revision을 지정 | 이전에 고정한 구매 행을 복사해 모든 상품의 점수와 순위를 다시 계산한다. |

## 이전 스냅샷의 입력으로 다시 계산하기

예를 들어 r1의 입력을 고정한 뒤 늦은 구매 10개가 도착했다. 수정한 계산식의 영향만 보려면, 수정된 배치로 r2를 실행할 때 다음 Job 파라미터를 전달한다.

```text
periodStart=2026-08-17
snapshotId=weekly-20260817-r2
sourceSnapshotId=weekly-20260817-r1,java.lang.String,false
```

`false`는 Spring Batch에서 이 파라미터를 JobInstance 식별에서 제외하는 표기다. r2의 실행이 중단돼 이어갈 때도 처음과 같은 세 값을 전달한다. r2에 늦은 구매까지 포함하려면 처음부터 `sourceSnapshotId`를 생략해 구매 원장을 읽는다.

원본은 같은 주의 더 낮은 revision이어야 하며 입력 고정이 완료돼 있어야 한다. 원본의 점수 계산이나 공개가 실패했어도 고정 입력은 사용할 수 있다. r2는 복사한 구매 행의 건수·상품 수·체크섬을 r1의 기록과 대조하고 입력 고정 시각도 이어받는다. 원본이 없거나 입력이 일치하지 않으면 복사를 롤백하고 기존 공개본을 유지한다. [재계산 테스트](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingRecalculationTest.java)

## 실행이 STARTED로 남았을 때

프로세스가 비정상 종료돼도 Batch 메타데이터에는 `STARTED`가 남을 수 있다. 이 프로젝트에는 경과 시간만으로 실행을 포기하는 작업이 없다. 운영자는 ECS 작업 상태를 조회해 실제 프로세스가 종료됐는지 확인한다. 같은 기간에 살아 있는 다른 실행이 없고, 기존 공개본과 미완성 결과를 어떻게 다룰지도 확인한다.

아래 명령의 `termination-confirmed=true`는 **운영자의 확인 표시**다. 애플리케이션이 ECS를 조회하거나 프로세스 종료를 자동으로 검증하지는 않는다. 아직 실행 중인 프로세스를 포기 처리하면 같은 기간 작업이 중복 실행될 수 있다.

## 종료가 확인된 실행 포기

이 복구 방식은 이전 JobInstance를 재시작하지 않고 새로운 snapshotId/revision으로 다시 시작하는 경우에 사용한다. 기존 JobExecution과 진행 중으로 남은 StepExecution을 `ABANDONED`로 기록한다. 스냅샷, 임시 점수, 공개 결과는 삭제하지 않는다. 같은 JobInstance는 다시 실행할 수 없고, 다른 revision의 새 JobInstance를 별도로 실행해야 한다.

예시는 개발 환경의 실행 방법이다. `EXECUTION_ID`는 확인한 실제 JobExecution ID로 바꾸고, DB와 프로필이 의도한 환경을 가리키는지 별도로 확인한다.

```bash
./gradlew :apps:commerce-batch:bootRun --args='--spring.batch.job.name=weeklyRanking --spring.batch.job.enabled=false --spring.batch.jdbc.initialize-schema=never --spring.jpa.hibernate.ddl-auto=none --weekly-ranking.recovery.execution-id=EXECUTION_ID --weekly-ranking.recovery.termination-confirmed=true'
```

`execution-id`를 지정한 복구 실행에서는 위 다섯 조건이 모두 충족되지 않으면 DB 초기화 전에 애플리케이션 시작을 거부한다. 특히 이 프로젝트의 `local/test` 기본 JPA 설정은 `create`이므로 반드시 `none`으로 지정해야 한다. 활성 상태로 남은 `weeklyRanking` 실행만 변경할 수 있다. 이 명령은 새 Job을 자동으로 실행하지 않는다.

## 확인 범위

같은 revision의 재시작 사례는 [입력 고정과 원장 정정](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingFreezeTest.java), [계산 중 마지막 커밋에서 재개](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/RankingJobRestartTest.java), [공개 단계만 재실행](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingPublishTest.java) 테스트에서 확인할 수 있다.

[STARTED 실행 복구 테스트](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingRecoveryTest.java)는 테스트 MySQL에 남겨 둔 실행 상태를 사용해 차단과 포기 후 새 작업을 확인한다. 실제 ECS 강제 종료·프로세스 확인·운영 DB 복구를 수행한 검증은 아니다.
