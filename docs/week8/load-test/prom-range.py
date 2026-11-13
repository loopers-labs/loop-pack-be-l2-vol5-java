"""Prometheus(:9090) HTTP API로 한 단계 구간의 시계열을 받아 CSV로 남긴다. Prometheus는 앱을 5초마다 긁는다(docker/grafana/prometheus.yml).

    python prom-range.py <시작 unix초> <끝 unix초> results/c3-prom.csv

열: time(unix초), series, value. 식은 grafana-dashboard.json 패널과 같다. 표준 라이브러리만 쓴다.
"""
import json
import sys
import urllib.parse
import urllib.request

PROM = "http://127.0.0.1:9090"
POOL = 'pool="mysql-main-pool"'
ISSUE = 'uri="/api/v1/coupon-campaigns/{campaignId}/issues"'
LIST = 'uri="/api/v1/coupons"'
QUERIES = {
    "hikari_active": f"max(hikaricp_connections_active{{{POOL}}})",
    "hikari_pending": f"max(hikaricp_connections_pending{{{POOL}}})",
    "hikari_max": f"max(hikaricp_connections_max{{{POOL}}})",
    "acquire_avg_ms": f"1000 * sum(rate(hikaricp_connections_acquire_seconds_sum{{{POOL}}}[15s]))"
                      f" / sum(rate(hikaricp_connections_acquire_seconds_count{{{POOL}}}[15s]))",
    "issue_rps": f"sum by (status) (rate(http_server_requests_seconds_count{{{ISSUE}}}[15s]))",
    "issue_p95_ms": f"1000 * histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{ISSUE}}}[15s])))",
    "list_rps": f"sum(rate(http_server_requests_seconds_count{{{LIST}}}[15s]))",
    "list_p95_ms": f"1000 * histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{LIST}}}[15s])))",
}


def main():
    start, end, out = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("time,series,value\n")
        for name, query in QUERIES.items():
            params = urllib.parse.urlencode({"query": query, "start": start, "end": end, "step": "5"})
            with urllib.request.urlopen(f"{PROM}/api/v1/query_range?{params}", timeout=30) as res:
                body = json.load(res)
            for result in body["data"]["result"]:
                status = result["metric"].get("status")
                label = f"{name}_{status}" if status else name
                for t, v in result["values"]:
                    f.write(f"{int(float(t))},{label},{v}\n")


if __name__ == "__main__":
    main()
