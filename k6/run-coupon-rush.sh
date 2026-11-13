#!/usr/bin/env bash
# 선착순 쿠폰 몰림 부하를 C 하나로 한 번 실행한다. docker/infra-compose.yml 의 MySQL·Redis·Kafka 가 떠 있어야 한다.
#   사용: k6/run-coupon-rush.sh <C> [QUOTA] [USERS]
# 서버를 C 로 기동(ddl-auto create 라 DB 가 비워진다) → 사용자 시드 → k6 → 수용·연결 샘플링 → DB·메트릭 확인 → 서버 종료.
set -euo pipefail

C="${1:?C(동시 DB 실행 수)를 넘겨 주세요}"
QUOTA="${2:-100}"
USERS="${3:-1000}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/k6/results"
LOG="$OUT/server-C$C.log"
MYSQL=(docker compose -f "$ROOT/docker/infra-compose.yml" exec -T mysql mysql -uapplication -papplication loopers -N -e)
REDIS=(docker exec redis-master redis-cli)
mkdir -p "$OUT"

cleanup() {
  [[ -n "${SAMPLER_PID:-}" ]] && kill "$SAMPLER_PID" 2>/dev/null || true
  [[ -n "${HIKARI_PID:-}" ]] && kill "$HIKARI_PID" 2>/dev/null || true
  [[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  # 이 실험이 만든 쿠폰 수용 키만 지운다.
  "${REDIS[@]}" --scan --pattern 'coupon-issue:*' | xargs -r -n 100 "${REDIS[@]}" del > /dev/null || true
}
trap cleanup EXIT

"${REDIS[@]}" --scan --pattern 'coupon-issue:*' | xargs -r -n 100 "${REDIS[@]}" del > /dev/null || true

echo "[1/5] 서버 기동 (C=$C)"
JAR="$(ls -t "$ROOT"/apps/commerce-api/build/libs/commerce-api-*.jar 2>/dev/null | grep -v plain | head -1)"
[[ -n "$JAR" ]] || { echo "먼저 ./gradlew :apps:commerce-api:bootJar 를 실행해 주세요"; exit 1; }
# gradle bootRun 을 거치지 않고 jar 를 직접 띄워, 종료할 때 서버 프로세스를 정확히 내린다.
java -jar "$JAR" --spring.profiles.active=local --coupon.issue.capacity="$C" --spring.jpa.show-sql=false > "$LOG" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 120); do
  curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1 && break
  sleep 1
done
curl -sf http://localhost:8081/actuator/health > /dev/null || { echo "서버가 뜨지 않았습니다. $LOG 확인"; exit 1; }

echo "[2/5] 사용자 $USERS 명 시드"
"${MYSQL[@]}" "SET SESSION cte_max_recursion_depth = $((USERS + 1));
  INSERT INTO users (point_balance, created_at, updated_at)
  WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $USERS)
  SELECT 0, NOW(6), NOW(6) FROM seq;" 2> /dev/null

echo "[3/5] k6 실행 + running·Hikari 샘플링"
RUNNING="$OUT/running-C$C.txt"
HIKARI="$OUT/hikari-C$C.txt"
: > "$RUNNING"; : > "$HIKARI"
# running 은 Redis 만 읽어 촘촘히, Hikari 는 prometheus 응답이 무거워 따로 샘플링한다.
( while true; do "${REDIS[@]}" --scan --pattern 'coupon-issue:*:running' | head -1 | xargs -r "${REDIS[@]}" scard >> "$RUNNING" 2>/dev/null || true; sleep 0.005; done ) &
SAMPLER_PID=$!
( while true; do curl -s http://localhost:8081/actuator/prometheus | awk '/^hikaricp_connections_(active|pending)\{/ {printf "%s ", $2} END {print ""}' >> "$HIKARI"; sleep 0.05; done ) &
HIKARI_PID=$!
k6 run -q -e QUOTA="$QUOTA" -e USERS="$USERS" -e SUMMARY_OUT="$OUT/summary-C$C.json" "$ROOT/k6/coupon-rush.js"
kill "$SAMPLER_PID" "$HIKARI_PID" 2>/dev/null || true
SAMPLER_PID=""

echo "[4/5] DB·메트릭 확인"
"${MYSQL[@]}" "SELECT CONCAT('coupons=', (SELECT COUNT(*) FROM coupons),
  ' issued_count=', (SELECT issued_count FROM coupon_campaigns ORDER BY id DESC LIMIT 1),
  ' tickets=', (SELECT COUNT(*) FROM coupon_issue_tickets),
  ' quota=', (SELECT total_quota FROM coupon_campaigns ORDER BY id DESC LIMIT 1));" 2> /dev/null
awk 'BEGIN{r=0} {if($1>r)r=$1} END{printf "running 최대=%d (샘플 %d개)\n", r, NR}' "$RUNNING"
awk 'BEGIN{a=0;p=0} {if($1>a)a=$1; if($2>p)p=$2} END{printf "Hikari 활성 최대=%d, 대기 최대=%d (샘플 %d개)\n", a, p, NR}' "$HIKARI"
sleep 2
echo "부하 종료 2초 뒤 Hikari 활성=$(curl -s http://localhost:8081/actuator/prometheus | awk '/^hikaricp_connections_active\{/ {print $2}')"
curl -s http://localhost:8081/actuator/prometheus > "$OUT/prometheus-C$C.txt"
python3 "$ROOT/k6/histogram_quantile.py" "$OUT/prometheus-C$C.txt" coupon_issue_write_seconds

echo "[5/5] 서버 종료와 정리"
