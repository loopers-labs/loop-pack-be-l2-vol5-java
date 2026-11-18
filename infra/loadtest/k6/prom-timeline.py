"""모니터 서버에서 실행한다 (run-issue-spike.sh 가 ssh 로 넘긴다).
START~END(epoch 초) 구간을 1초 간격으로 뽑아 TSV 로 출력한다. 비율 값은 3초 창 rate 라 1~2초 늦게 따라온다.
사용: python3 - START END < prom-timeline.py
"""
import json
import sys
import urllib.parse
import urllib.request

START, END = int(sys.argv[1]), int(sys.argv[2])
ISSUE = 'uri="/api/v1/promotions/{promotionId}/issues"'

COLUMNS = [
    ("k6_req_per_s", 'sum(rate(k6_http_reqs_total[3s]))'),
    ("app_req_per_s", f'sum(rate(http_server_requests_seconds_count{{{ISSUE}}}[3s]))'),
    ("app_p99_ms", f'1000 * histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{{ISSUE}}}[3s])))'),
    ("app_p50_ms", f'1000 * histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{{{ISSUE}}}[3s])))'),
    ("tomcat_busy", 'sum(tomcat_threads_busy_threads)'),
    ("hikari_active", 'sum(hikaricp_connections_active)'),
    ("hikari_pending", 'sum(hikaricp_connections_pending)'),
    ("app_cpu_pct_max", 'max(100 * (1 - avg by (host) (rate(node_cpu_seconds_total{host=~"app-.*",mode="idle"}[3s]))))'),
    ("mysql_cpu_pct", '100 * (1 - avg(rate(node_cpu_seconds_total{host="mysql",mode="idle"}[3s])))'),
    ("mysql_threads_running", 'mysql_global_status_threads_running'),
    ("row_lock_current_waits", 'mysql_global_status_innodb_row_lock_current_waits'),
    ("row_lock_waits_per_s", 'rate(mysql_global_status_innodb_row_lock_waits[3s])'),
    ("mysql_commits_per_s", 'rate(mysql_global_status_commands_total{command="commit"}[3s])'),
    ("mysql_rollbacks_per_s", 'rate(mysql_global_status_commands_total{command="rollback"}[3s])'),
]


def query_range(promql):
    params = urllib.parse.urlencode({"query": promql, "start": START, "end": END, "step": "1s"})
    with urllib.request.urlopen(f"http://localhost:9090/api/v1/query_range?{params}") as res:
        result = json.load(res)["data"]["result"]
    if not result:
        return {}
    return {int(float(t)): float(v) for t, v in result[0]["values"]}


series = {name: query_range(q) for name, q in COLUMNS}
print("t_s\t" + "\t".join(name for name, _ in COLUMNS))
for t in range(START, END + 1):
    row = []
    for name, _ in COLUMNS:
        v = series[name].get(t)
        row.append("" if v is None or v != v else f"{v:.1f}")
    print(f"{t - START}\t" + "\t".join(row))
