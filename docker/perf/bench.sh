#!/usr/bin/env bash
# 같은 SQL을 warm-up 뒤 N번 실행해, 서버가 기록한 실행 시간 분포와 읽은 행·정렬한 행을 출력한다.
# 시간은 performance_schema.events_statements_history_long 의 TIMER_WAIT (네트워크·클라이언트 시간 제외).
#
# 사용: docker/perf/bench.sh <라벨> "<SQL>" [N=50] [WARMUP=5]
#   예: docker/perf/bench.sh "목록·인덱스없음" "select ... from product pm1_0 where pm1_0.brand_id=18 ..."
# 출력: 실행 계획(EXPLAIN), 실제 실행 계획(EXPLAIN ANALYZE), 결과 해시(전후 동일성 비교용), 시간·행 통계
set -euo pipefail

LABEL=$1
SQL=$2
N=${3:-50}
WARMUP=${4:-5}
CONTAINER=loopers-perf-mysql

app() { docker exec -i "$CONTAINER" mysql -h127.0.0.1 -uapplication -papplication loopers_perf "$@" 2>/dev/null; }
root() { docker exec -i "$CONTAINER" mysql -h127.0.0.1 -uroot -proot "$@" 2>/dev/null; }

echo "=== $LABEL"
echo "--- EXPLAIN"
app -t -e "EXPLAIN $SQL"
echo "--- EXPLAIN ANALYZE"
app -N -r -e "EXPLAIN ANALYZE $SQL"
echo "--- 결과 해시 (행 정렬 후 md5, 행 수)"
app -N -e "$SQL" | sort | md5sum | cut -c1-12
app -N -e "$SQL" | wc -l

root -N -e "UPDATE performance_schema.setup_consumers SET ENABLED = 'YES' WHERE NAME = 'events_statements_history_long'"
for _ in $(seq "$WARMUP"); do echo "$SQL;"; done | app -N >/dev/null
root -N -e "TRUNCATE TABLE performance_schema.events_statements_history_long"
for _ in $(seq "$N"); do echo "$SQL;"; done | app -N >/dev/null

PREFIX=${SQL:0:80}
echo "--- 실행 ${N}회 통계 (warm-up ${WARMUP}회 제외)"
root -t -e "
WITH t AS (
    SELECT TIMER_WAIT / 1e9 AS ms, ROWS_EXAMINED, ROWS_SENT, SORT_ROWS, SORT_MERGE_PASSES, NO_INDEX_USED,
           ROW_NUMBER() OVER (ORDER BY TIMER_WAIT) AS rn, COUNT(*) OVER () AS n
    FROM performance_schema.events_statements_history_long
    WHERE CURRENT_SCHEMA = 'loopers_perf' AND SQL_TEXT LIKE '${PREFIX}%'
)
SELECT MAX(n) AS n,
       ROUND(AVG(ms), 2) AS avg_ms,
       ROUND(MIN(ms), 2) AS min_ms,
       ROUND(MAX(CASE WHEN rn = CEIL(n * 0.5) THEN ms END), 2) AS p50_ms,
       ROUND(MAX(CASE WHEN rn = CEIL(n * 0.95) THEN ms END), 2) AS p95_ms,
       ROUND(MAX(ms), 2) AS max_ms,
       ROUND(AVG(ROWS_EXAMINED)) AS rows_examined,
       ROUND(AVG(ROWS_SENT)) AS rows_sent,
       ROUND(AVG(SORT_ROWS)) AS sort_rows,
       MAX(SORT_MERGE_PASSES) AS sort_merge_passes,
       MAX(NO_INDEX_USED) AS no_index_used
FROM t;"
