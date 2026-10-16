-- 앱 커넥션(PROCESSLIST_USER = 'application')에서 실행된 문장 목록과 개수 (root 로 실행)
-- 측정용 mysql 클라이언트는 root 로 접속하므로 아래 조건에 걸리지 않는다.

-- 1. 실행 순서대로 문장 목록 (파라미터는 리터럴로 채워진 형태)
SELECT e.EVENT_ID,
       t.PROCESSLIST_ID AS conn,
       e.EVENT_NAME,
       ROUND(e.TIMER_WAIT / 1e9, 3) AS ms,
       e.ROWS_SENT,
       e.ROWS_EXAMINED,
       e.NO_INDEX_USED,
       e.SQL_TEXT
  FROM performance_schema.events_statements_history_long e
  JOIN performance_schema.threads t ON e.THREAD_ID = t.THREAD_ID
 WHERE t.PROCESSLIST_USER = 'application'
 ORDER BY e.EVENT_ID\G

-- 2. 계정별 문장 종류 카운터 (SELECT 개수 = statement/sql/select 의 COUNT_STAR)
SELECT USER, EVENT_NAME, COUNT_STAR
  FROM performance_schema.events_statements_summary_by_user_by_event_name
 WHERE COUNT_STAR > 0
 ORDER BY USER, EVENT_NAME;

-- 3. 커넥션 구분 근거 (앱: MySQL Connector/J, 측정 클라이언트: libmysql)
SELECT t.PROCESSLIST_ID, t.PROCESSLIST_USER, a.ATTR_NAME, a.ATTR_VALUE
  FROM performance_schema.threads t
  JOIN performance_schema.session_connect_attrs a ON a.PROCESSLIST_ID = t.PROCESSLIST_ID
 WHERE a.ATTR_NAME IN ('_client_name', 'program_name')
 ORDER BY t.PROCESSLIST_ID;
