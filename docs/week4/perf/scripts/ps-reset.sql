-- 문장 이력, 계정별 카운터, digest 별 카운터 초기화 (root 로 실행). 각 측정 구간 직전에 실행한다.
TRUNCATE performance_schema.events_statements_history_long;
TRUNCATE performance_schema.events_statements_summary_by_user_by_event_name;
TRUNCATE performance_schema.events_statements_summary_by_digest;

SELECT COUNT(*) AS history_rows_after_reset
  FROM performance_schema.events_statements_history_long;
