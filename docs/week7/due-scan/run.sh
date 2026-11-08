#!/usr/bin/env bash
# relay 의 due 조회(findDue)가 이미 보낸 행이 쌓였을 때 몇 행을 읽는지, 인덱스 두 가지로 잰다.
# 행 1..1,000,000 은 PUBLISHED, 1,000,001..1,100,000(구간 R)은 시나리오마다 상태를 바꾼다.
# 실행: 저장소 루트에서 `bash docs/week7/due-scan/run.sh`
# 필요: docker. MySQL 8.0 을 이 실행만의 컨테이너로 띄우고 지운다
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
RESULT="${ROOT}/docs/week7/due-scan/result.md"
MYSQL="loopers-due-scan-mysql-$(date +%Y%m%d%H%M%S)"

cleanup() { docker rm -f "${MYSQL}" >/dev/null 2>&1 || true; }
trap cleanup EXIT

sql() { docker exec -i -e MYSQL_PWD=root "${MYSQL}" mysql -uroot -N -B t; }

docker run -d --rm --name "${MYSQL}" -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=t mysql:8.0 >/dev/null
until echo "select 1" | sql >/dev/null 2>&1; do sleep 2; done
VERSION="$(echo "select version()" | sql)"

sql <<'SQL'
create table order_event_outbox (
  id bigint not null auto_increment,
  created_at datetime(6) not null,
  updated_at datetime(6) not null,
  event_id varchar(255) not null,
  order_id bigint not null,
  event_type varchar(50) not null,
  schema_version int not null,
  topic varchar(255) not null,
  record_key varchar(255) not null,
  payload mediumblob not null,
  status varchar(20) not null,
  attempt_count int not null,
  next_attempt_at datetime(6) not null default (utc_timestamp(6)),
  retry_since datetime(6) not null default (utc_timestamp(6)),
  last_error varchar(1000),
  published_at datetime(6),
  primary key (id),
  unique key uk_outbox_event_id (event_id)
) engine=InnoDB;
set session cte_max_recursion_depth = 2000000;
insert into order_event_outbox
  (created_at, updated_at, event_id, order_id, event_type, schema_version, topic, record_key,
   payload, status, attempt_count, next_attempt_at, published_at)
with recursive seq(n) as (select 1 union all select n + 1 from seq where n < 1100000)
select now(6), now(6), uuid(), n, 'OrderConfirmed', 1, 'order-events', n, repeat('x', 500),
       'PUBLISHED', 1, utc_timestamp(6) - interval 2 day + interval n * 0.1 second, utc_timestamp(6)
from seq;
SQL

scenario_sql() {
  case "$1" in
    steady) echo "update order_event_outbox set status = if(id > 1099950, 'PENDING', 'PUBLISHED'), next_attempt_at = utc_timestamp(6) - interval 1 second where id > 1000000" ;;
    backlog) echo "update order_event_outbox set status = 'PENDING', next_attempt_at = utc_timestamp(6) - interval 1 second where id > 1000000" ;;
    outage) echo "update order_event_outbox set status = 'PENDING', next_attempt_at = utc_timestamp(6) + interval 1 day where id > 1000000" ;;
    recovering) echo "update order_event_outbox set status = 'PENDING', next_attempt_at = if(id > 1099950, utc_timestamp(6) - interval 1 second, utc_timestamp(6) + interval 1 day) where id > 1000000" ;;
  esac
}
describe() {
  case "$1" in
    steady) echo "평상시: PENDING 50 건이 모두 보낼 때가 됨" ;;
    backlog) echo "장애 뒤 밀림: PENDING 10 만 건이 모두 보낼 때가 됨" ;;
    outage) echo "장애 중: PENDING 10 만 건이 모두 backoff 중(고를 행 0)" ;;
    recovering) echo "회복 중: 앞쪽 PENDING 99,950 건은 backoff 중, 뒤쪽 50 건만 보낼 때가 됨" ;;
  esac
}
index_cols() {
  case "$1" in
    before) echo "(status, next_attempt_at, id)" ;;
    after) echo "(status, id, next_attempt_at)" ;;
  esac
}
SCENARIOS="steady backlog outage recovering"

DUE_QUERY="select * from order_event_outbox where status = 'PENDING' and next_attempt_at <= utc_timestamp(6) order by id limit 100"

{
  echo "# due 조회 스캔 측정 결과"
  echo
  echo "\`bash docs/week7/due-scan/run.sh\` 가 만든다. MySQL ${VERSION}. PUBLISHED 1,000,000 행 뒤 구간 R(100,000 행)의 상태를 시나리오마다 바꾼다."
  echo
  echo "조회: \`${DUE_QUERY}\`"
  echo
  echo "| 인덱스 | 시나리오 | 고른 행 | 페이지 접근 | 실행 시간 | 접근 방식 |"
  echo "|---|---|---|---|---|---|"
} > "${RESULT}"

DETAILS=""
for variant in before after; do
  echo "alter table order_event_outbox add index idx_outbox_due $(index_cols "${variant}")" | sql
  for scenario in ${SCENARIOS}; do
    printf '%s; analyze table order_event_outbox;\n' "$(scenario_sql "${scenario}")" | sql >/dev/null
    until [ "$(echo "select count from information_schema.innodb_metrics where name = 'trx_rseg_history_len'" | sql)" -eq 0 ]; do sleep 1; done
    PAGE_READS="select variable_value from performance_schema.global_status where variable_name = 'Innodb_buffer_pool_read_requests'"
    measured="$(printf '%s;\n%s;\nexplain analyze %s;\n%s;\n' "${PAGE_READS}" "${PAGE_READS}" "${DUE_QUERY}" "${PAGE_READS}" | sql)"
    idle_start="$(echo "${measured}" | sed -n 1p)"
    before="$(echo "${measured}" | sed -n 2p)"
    after="$(echo "${measured}" | tail -1)"
    pages="$(( after - before - (before - idle_start) ))"
    plan="$(echo "${measured}" | sed -n '3,$p' | sed '$d' | sed 's/\\n/\n/g')"
    picked="$(echo "${plan}" | grep -m1 -o 'rows=[0-9]* loops' | sed 's/rows=//; s/ loops//' || true)"
    elapsed="$(echo "${plan}" | grep -m1 -o 'actual time=[0-9.e+]*\.\.[0-9.e+]*' | sed 's/.*\.\.//' || true)"
    access="$(echo "${plan}" | grep -m1 -o -E '(Index range scan|Index scan|Index lookup|Table scan|Covering index [a-z ]*(scan|lookup)) on [a-z_]+( using [A-Za-z_]+)?' | sed 's/ on order_event_outbox//' || true)"
    echo "| \`$(index_cols "${variant}")\` | $(describe "${scenario}") | ${picked} | ${pages} | ${elapsed} ms | ${access} |" >> "${RESULT}"
    DETAILS+=$'\n'"### \`$(index_cols "${variant}")\` · ${scenario}"$'\n\n```\n'"${plan}"$'\n```\n'
  done
  echo "alter table order_event_outbox drop index idx_outbox_due" | sql
done

{
  echo
  echo "페이지 접근은 \`EXPLAIN ANALYZE\` 한 번 동안 늘어난 \`Innodb_buffer_pool_read_requests\` 에서, 상태만 읽었을 때 늘어난 값을 뺀 것이다. 이 컨테이너에는 다른 부하가 없고, 시나리오의 update 가 남긴 undo 를 purge 가 다 지운 뒤(\`trx_rseg_history_len = 0\`)에 잰다. 인덱스 조건 푸시다운으로 걸러진 항목은 \`Handler_read_*\` 에 잡히지 않아 이 값을 쓴다. 실행 시간은 \`EXPLAIN ANALYZE\` 최상위 노드의 끝 시각이고 buffer pool 상태에 따라 흔들린다."
  echo
  echo "## 실행 계획"
  echo "${DETAILS}"
} >> "${RESULT}"

echo "결과: ${RESULT}"
