-- performance_schema 상태 확인과 events_statements_history_long consumer 켜기 (root 로 실행)
-- MySQL 런타임 설정이라 컨테이너 재시작 시 기본값(OFF)으로 돌아간다. 개선 후 측정 때도 같은 설정으로 잰다.
SHOW VARIABLES WHERE Variable_name IN (
    'performance_schema',
    'performance_schema_max_sql_text_length',
    'performance_schema_events_statements_history_long_size'
);

SELECT NAME, ENABLED AS enabled_before
  FROM performance_schema.setup_consumers
 WHERE NAME LIKE 'events_statements%';

UPDATE performance_schema.setup_consumers
   SET ENABLED = 'YES'
 WHERE NAME = 'events_statements_history_long';

SELECT NAME, ENABLED AS enabled_after
  FROM performance_schema.setup_consumers
 WHERE NAME LIKE 'events_statements%';
