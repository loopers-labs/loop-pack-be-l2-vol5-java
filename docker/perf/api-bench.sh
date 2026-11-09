#!/usr/bin/env bash
# 같은 API를 warm-up 뒤 N번 순차 호출해, 클라이언트가 본 응답 시간 분포를 출력한다.
# 앱은 실습 DB에 ddl-auto=none 으로 띄워 둔 상태여야 한다 (docs/week4/perf-environment.md 9절).
#
# 사용: docker/perf/api-bench.sh <라벨> "<URL>" [N=50] [WARMUP=5] [응답 저장 경로]
set -euo pipefail

LABEL=$1
URL=$2
N=${3:-50}
WARMUP=${4:-5}
BODY_OUT=${5:-}

for _ in $(seq "$WARMUP"); do curl -s -o /dev/null "$URL"; done
if [ -n "$BODY_OUT" ]; then curl -s "$URL" > "$BODY_OUT"; fi

for _ in $(seq "$N"); do curl -s -o /dev/null -w '%{http_code} %{time_total}\n' "$URL"; done |
    awk -v label="$LABEL" '
        { codes[$1]++; ms[NR] = $2 * 1000; sum += $2 * 1000 }
        END {
            n = asort(ms)
            p50 = ms[int(n * 0.5 + 0.999999)]
            p95 = ms[int(n * 0.95 + 0.999999)]
            status = ""; for (c in codes) status = status c "x" codes[c] " "
            printf "%s | n=%d status=%s| avg=%.1fms min=%.1fms p50=%.1fms p95=%.1fms max=%.1fms\n", label, n, status, sum / n, ms[1], p50, p95, ms[n]
        }'
