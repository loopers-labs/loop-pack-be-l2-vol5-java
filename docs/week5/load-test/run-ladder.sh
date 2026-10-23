#!/usr/bin/env bash
# 한 단계(PG 상태)에서 VU를 차례로 올리며 1분씩 측정한다. 단계마다 지표 수집을 같이 돌린다.
#   ./run-ladder.sh 0 10 50 100 200     → results/stage0-vu10.json, stage0-vu10-metrics.csv …
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
K6="${K6:-/c/Program Files/k6/k6.exe}"
STAGE="${1:?단계 번호}"; shift
DURATION="${DURATION:-1m}"

for vus in "$@"; do
  name="stage${STAGE}-vu${vus}"
  "$DIR/sample-metrics.sh" "$DIR/results/${name}-metrics.csv" &
  sampler=$!
  "$K6" run --quiet -e VUS="$vus" -e DURATION="$DURATION" \
    --summary-export "$DIR/results/${name}.json" "$DIR/k6/confirm.js" > "$DIR/results/${name}.txt" 2>&1 || true
  kill "$sampler"
  echo "$name done"
done
