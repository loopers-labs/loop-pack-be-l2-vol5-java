#!/usr/bin/env bash
# 부하 측정용 테스트 PG(WireMock standalone)를 띄우고 단계별 응답 규칙으로 바꾼다.
#   ./pg-stub.sh start        18080 포트로 띄운다(앱 설정 pg.base-url과 같은 주소)
#   ./pg-stub.sh stage 0      단계 0~3의 응답 규칙으로 바꾸고 받은 요청 기록을 비운다
#   ./pg-stub.sh count        받은 거래 생성(create) 요청 수
#   ./pg-stub.sh stop
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS="$DIR/../results"
PG="http://127.0.0.1:18080"
JAVA="${JAVA21:-/c/Java/jdk-21.0.12.1+1}/bin/java"
JAR="$(find ~/.gradle/caches -name 'wiremock-standalone-3.13.1.jar' | head -1)"

case "${1:-}" in
  start)
    mkdir -p "$RESULTS"
    # 응답 지연(단계 2)이 스레드를 붙잡지 않게 비동기 응답을 켜고, 동시 요청을 받을 스레드를 넉넉히 둔다.
    "$JAVA" -jar "$JAR" --port 18080 --container-threads 300 \
      --async-response-enabled true --async-response-threads 100 --disable-banner \
      > "$RESULTS/pg-stub.log" 2>&1 &
    echo $! > "$RESULTS/pg-stub.pid"
    for _ in $(seq 1 60); do
      curl -sf "$PG/__admin/health" > /dev/null && { echo "pg-stub ready (pid $(cat "$RESULTS/pg-stub.pid"))"; exit 0; }
      sleep 0.5
    done
    echo "pg-stub did not start" >&2; exit 1
    ;;
  stage)
    stage="stage${2:?단계 번호(0~3)}"
    curl -sf -X DELETE "$PG/__admin/mappings" > /dev/null
    for mapping in "$DIR/$stage"/*.json; do
      curl -sf -X POST -H 'Content-Type: application/json' --data-binary @"$mapping" "$PG/__admin/mappings" > /dev/null
    done
    curl -sf -X DELETE "$PG/__admin/requests" > /dev/null
    echo "pg-stub → $stage"
    ;;
  count)
    curl -sf -X POST -H 'Content-Type: application/json' \
      -d '{"method":"POST","url":"/api/v1/payments"}' "$PG/__admin/requests/count"
    echo
    ;;
  stop)
    kill "$(cat "$RESULTS/pg-stub.pid")" && rm -f "$RESULTS/pg-stub.pid" && echo "pg-stub stopped"
    ;;
  *)
    echo "usage: $0 start|stage <0-3>|count|stop" >&2; exit 2
    ;;
esac
