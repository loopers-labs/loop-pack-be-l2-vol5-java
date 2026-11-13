#!/usr/bin/env bash
# 수용량 C 하나를 처음부터 측정한다: 앱 새로 기동(스키마 초기화, 캠페인 생성, 수용 열기) → 지표 수집 + k6 → Prometheus 구간 저장 → DB 확인.
#   ./run-stage.sh 3     → results/c3.json, c3.txt, c3-metrics.csv, c3-prom.csv, c3-window.txt, c3-db.txt, c3-app.log
#   VUS=3 PREFIX=vu-eq-c ./run-stage.sh 3   → 발급 VU를 C와 같게(자리 거절이 거의 없게) 둔 보조 측정, results/vu-eq-c-c3.*
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../../.." && pwd)"
K6="${K6:-/c/Program Files/k6/k6.exe}"
C="${1:?수용량 C}"
VUS="${VUS:-200}"; DURATION="${DURATION:-60s}"; LIST_RATE="${LIST_RATE:-20}"
R="$DIR/results"
N="${PREFIX:+$PREFIX-}c$C"
mkdir -p "$R"

stop_app() {
  local pid
  pid="$(powershell.exe -NoProfile -Command '(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).OwningProcess' | tr -d '\r')"
  [ -n "$pid" ] && powershell.exe -NoProfile -Command "Stop-Process -Id $pid -Force" > /dev/null || true
  for _ in $(seq 1 30); do curl -sf http://127.0.0.1:8081/actuator/health > /dev/null 2>&1 || break; sleep 1; done
}

stop_app
# 입력·출력을 모두 파일로 돌린다 — 그러지 않으면 앱 프로세스가 이 스크립트의 출력을 붙잡아 스크립트가 끝나지 않는다(5주차와 같다).
(cd "$ROOT" && nohup ./gradlew :apps:commerce-api:bootRun \
  --args="--spring.profiles.active=local,coupon-load --spring.jpa.show-sql=false --server.tomcat.mbeanregistry.enabled=true --coupon.admission.capacity=$C" \
  < /dev/null > "$R/$N-app.log" 2>&1 &)
CAMPAIGN_ID=""
for _ in $(seq 1 150); do
  CAMPAIGN_ID="$(grep -o 'coupon-load campaignId=[0-9]*' "$R/$N-app.log" 2>/dev/null | head -1 | cut -d= -f2 || true)"
  [ -n "$CAMPAIGN_ID" ] && break
  sleep 2
done
[ -n "$CAMPAIGN_ID" ] || { echo "앱이 준비되지 않았다 — $R/$N-app.log"; exit 1; }
sleep 10   # Prometheus가 새 프로세스를 두어 번 긁게 둔다(앞 단계와 구간이 섞이지 않게)
echo "C=$C campaignId=$CAMPAIGN_ID ready $(date +%H:%M:%S)"

"$DIR/sample-metrics.sh" "$R/$N-metrics.csv" &
sampler=$!
START=$(date +%s)
"$K6" run --quiet -e CAMPAIGN_ID="$CAMPAIGN_ID" -e VUS="$VUS" -e DURATION="$DURATION" -e LIST_RATE="$LIST_RATE" \
  --summary-export "$R/$N.json" "$DIR/k6/issue.js" > "$R/$N.txt" 2>&1 || true
END=$(date +%s)
kill "$sampler" 2>/dev/null || true
echo "$START $END" > "$R/$N-window.txt"
sleep 10   # 마지막 구간이 긁히도록
python "$DIR/prom-range.py" "$((START - 10))" "$((END + 10))" "$R/$N-prom.csv" || echo "Prometheus 구간 저장 실패"

# 끝난 뒤 DB 확인: 발급 수 = 실제 쿠폰 수, 사용자당 한 장
docker exec docker-mysql-1 mysql -uapplication -papplication loopers -e "
  SELECT c.id, c.total_quota, c.issued_count, (SELECT COUNT(*) FROM issued_coupon i WHERE i.campaign_id = c.id) AS coupons FROM coupon_campaign c;
  SELECT COUNT(*) AS dup_users FROM (SELECT user_id FROM issued_coupon GROUP BY campaign_id, user_id HAVING COUNT(*) > 1) d;" \
  > "$R/$N-db.txt" 2>/dev/null || echo "DB 확인 실패" > "$R/$N-db.txt"
echo "C=$C done $(date +%H:%M:%S)"
