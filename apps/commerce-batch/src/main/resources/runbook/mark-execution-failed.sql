-- 멈춘 실행 정리 (W9 D-7). 프로세스가 kill·OOM·배포로 죽어 STARTED(또는 UNKNOWN)로 남은 실행을 FAILED로 바꾼다.
-- 그래야 같은 명령으로 재시작할 수 있다 (Spring Batch 5.2는 실행 중으로 보이는 작업의 재시작을 거절한다).
--
-- 실행 전에 사람이 확인한다 (docs/week9/plan.md D-7 "운영 절차"):
--   0. 그 프로세스가 정말 죽었는지 (프로세스 목록, 배포 기록, 로그 마지막 줄, LAST_UPDATED가 오래 멈췄는지)
--   1. 업무 상태 (ranking_snapshot.status, ranking_publication이 가리키는 snapshot)
--
-- VERSION + 1은 안전장치다. 죽은 줄 알았던 프로세스가 살아 있으면, 그 프로세스가 다음 chunk를 commit할 때
-- 같은 TX에서 step 기록을 저장하다 VERSION이 달라 실패하고, 그 chunk의 업무 쓰기까지 rollback된다.
--
-- :jobExecutionId 에 정리할 JOB_EXECUTION_ID를 넣는다. 테스트(StuckExecutionRecovery)가 이 파일을 그대로 실행한다.

UPDATE BATCH_STEP_EXECUTION
   SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(6), VERSION = VERSION + 1
 WHERE JOB_EXECUTION_ID = :jobExecutionId
   AND STATUS IN ('STARTING', 'STARTED', 'STOPPING', 'UNKNOWN');

UPDATE BATCH_JOB_EXECUTION
   SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(6), VERSION = VERSION + 1
 WHERE JOB_EXECUTION_ID = :jobExecutionId;
