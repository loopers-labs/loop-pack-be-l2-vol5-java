-- 멈춘 실행 정리 (docs/week9/design.md 6절, ADR 0092)
-- 프로세스가 강제 종료되면 실행 기록이 STARTED·UNKNOWN으로 남아 같은 parameter의 재실행이 거부된다.
-- 실행 전에 확인한다.
--   1. 그 실행을 띄운 commerce-batch 프로세스가 정말 끝났는가 (프로세스 목록, 배포 기록, 로그 마지막 줄)
--   2. 업무 상태: ranking_publication이 가리키는 snapshot, ranking_snapshot.status, ranking_entry의 결과 checksum
-- VERSION을 1 올린다. 끝난 줄 알았던 실행이 살아 있으면, 그 실행이 다음 chunk를 commit하면서
-- 같은 TX에서 Step 기록을 저장할 때 version이 맞지 않아 실패하고 그 chunk 전체가 rollback된다.
-- :jobExecutionId 자리에 정리할 JobExecution ID를 넣는다.
UPDATE BATCH_STEP_EXECUTION
   SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(6), VERSION = VERSION + 1
 WHERE JOB_EXECUTION_ID = :jobExecutionId
   AND STATUS IN ('STARTING', 'STARTED', 'STOPPING', 'UNKNOWN');
UPDATE BATCH_JOB_EXECUTION
   SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(6), VERSION = VERSION + 1
 WHERE JOB_EXECUTION_ID = :jobExecutionId
   AND STATUS IN ('STARTING', 'STARTED', 'STOPPING', 'UNKNOWN');
