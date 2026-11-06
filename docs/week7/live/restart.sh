#!/usr/bin/env bash
# 앱 하나를 멈추거나 다시 띄운다. 사용: bash docs/week7/live/restart.sh {api|streamer} {stop|kill|start}
#   stop = 정상 종료(SIGTERM) · kill = 강제 종료(kill -9) · start = 같은 jar·설정으로 다시 띄움
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"; RUN="${TMPDIR:-/tmp}/w7-live"
APP="$1"; ACT="$2"
case "$APP" in api) JAR=commerce-api.jar; READY="curl -sf localhost:8081/actuator/health";;
              streamer) JAR=commerce-streamer.jar; READY="curl -sf localhost:8086/actuator/health";;
              *) echo "api 또는 streamer"; exit 1;; esac
PID="$(cat "$RUN/$APP.pid" 2>/dev/null || true)"
case "$ACT" in
  stop) kill "$PID"; echo "[live] $APP 정상 종료 요청 (pid $PID)";;
  kill) kill -9 "$PID"; echo "[live] $APP kill -9 (pid $PID)";;
  start)
    java -jar "$RUN/$JAR" --spring.profiles.active=local --spring.config.additional-location=file:$REPO/docs/week7/live/config-live.yml >> "$RUN/$APP.log" 2>&1 &
    echo $! > "$RUN/$APP.pid"
    for _ in $(seq 1 120); do $READY >/dev/null 2>&1 && { echo "[live] $APP 다시 뜸 (pid $(cat "$RUN/$APP.pid"))"; exit 0; }; sleep 1; done
    echo "[live] $APP 기동 시간 초과 — $RUN/$APP.log"; exit 1;;
  *) echo "stop · kill · start"; exit 1;;
esac
