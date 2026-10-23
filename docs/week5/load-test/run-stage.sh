#!/usr/bin/env bash
# 한 단계를 처음부터 측정한다: 테스트 PG 규칙 교체 → 앱 새로 기동(스키마·seed·회로 상태 초기화) → k6 → 결과 저장.
# 회수 관찰과 규칙 확인은 단계마다 다르므로 이 스크립트 밖에서 한다.
#   ./run-stage.sh 1 100        → 단계 1, VU 100, 1분
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../../.." && pwd)"
STAGE="${1:?단계 번호}"; VUS="${2:-100}"

stop_app() {
  local pid
  pid="$(powershell.exe -NoProfile -Command '(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).OwningProcess' | tr -d '\r')"
  [ -n "$pid" ] && powershell.exe -NoProfile -Command "Stop-Process -Id $pid -Force" > /dev/null || true
}

stop_app
"$DIR/pg-stub/pg-stub.sh" stage "$STAGE"
# 입력·출력을 모두 파일로 돌려 놓는다 — 그러지 않으면 앱 프로세스가 이 스크립트의 출력을 붙잡아 스크립트가 끝나지 않는다.
(cd "$ROOT" && nohup ./gradlew :apps:commerce-api:bootRun \
  --args='--spring.profiles.active=local,seed --spring.jpa.show-sql=false --server.tomcat.mbeanregistry.enabled=true' \
  < /dev/null > "$DIR/results/app.log" 2>&1 &)
for _ in $(seq 1 150); do
  curl -sf http://127.0.0.1:8081/actuator/health > /dev/null 2>&1 && break
  sleep 2
done
echo "app ready $(date +%H:%M:%S)"
"$DIR/run-ladder.sh" "$STAGE" "$VUS"
echo "load end $(date +%H:%M:%S)"
