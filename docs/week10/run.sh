#!/usr/bin/env bash
# W10 결제 사고 실험 — 한 번의 실행이 같은 DB·같은 PG 거래 저장소를 유지한 채 끝까지 간다 (docs/week10/plan.md D-1).
#
#   준비      MySQL · Redis · Kafka 3 · Tempo · Prometheus · Grafana · pg-sim · commerce-api · commerce-streamer
#   BASELINE  응답 유실 꺼짐 · 정상 집합 10건 확정
#   FAULT     장애 집합 10건 중 2·5·8번째만 응답 유실 → 확정 7 · 결제 UNKNOWN 3 · PG SUCCESS 10
#   증거 보존  health · 업무 지표 · DB · PG 조회 · 경계 로그 · trace (읽기만, reconcile 전에)
#   RECOVERY  응답 유실 해제 → 같은 3건에 고객 reconcile API (create 없음) → Outbox → Kafka → 구매 활동
#             → 같은 3건 한 번 더 + 보관한 callback 전송 → 변화 없음
#   RANKING   batch jar로 새 snapshot → 랭킹 API 점수 20
#
# 검사는 check 함수 하나로 한다. 틀리면 그 자리에서 멈추고 자원·기록을 남긴다 (조사용).
# 결과: docs/week10/results/<실행 ID>/ (plan.md D-9). 끝에 "검사 N개 중 N개 통과".
#
# 실행: 저장소 루트에서  bash docs/week10/run.sh
#       KEEP=1 bash docs/week10/run.sh  → 끝나도 자원을 남긴다 (Grafana에서 지표·trace를 보려면)
set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
W10="$REPO/docs/week10"
RUN="w10-$(date +%Y%m%d-%H%M%S)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/${RUN}.XXXX")"
OUT="$W10/results/$RUN"
KEEP="${KEEP:-0}"

MYSQL_PORT=33329
REDIS_PORT=36379
KAFKA_PORTS=(39292 39293 39294)
API_PORT=18080
API_MGMT_PORT=18081
TEMPO_HTTP_PORT=33200
TEMPO_OTLP_PORT=34318
PROMETHEUS_PORT=39090
GRAFANA_PORT=33000
PG_PORT=8082

NET="$RUN-net"
CONTAINERS=("$RUN-mysql" "$RUN-redis" "$RUN-kafka-1" "$RUN-kafka-2" "$RUN-kafka-3" "$RUN-tempo" "$RUN-prometheus" "$RUN-grafana")
DB=loopers_w10
API="http://127.0.0.1:$API_PORT"
MGMT="http://127.0.0.1:$API_MGMT_PORT"
PG="http://127.0.0.1:$PG_PORT"
TEMPO="http://127.0.0.1:$TEMPO_HTTP_PORT"
PG_ACCOUNT=135135                 # 로컬 PG 계정값 (application.yml pg.local.user-id와 같은 fixture 값). 증거 파일에 쓰지 않는다
CARD_NO="1234-5678-9814-1451"     # 테스트 카드 번호. 증거·로그에 남으면 안 된다 — 끝에서 grep으로 검사한다
USER_ID=1
PRODUCT_ID=1

mkdir -p "$OUT"/{ids,1-baseline,2-fault/traces,3-recovery/traces,4-ranking,logs}
PIDS=()
CHECKS="$OUT/checks.tsv"
printf 'name\texpected\tactual\tresult\n' > "$CHECKS"
CHECK_TOTAL=0

log() { echo "[w10] $*"; }

copy_logs() {
  # Spring Security가 시작할 때 찍는 임시 개발용 비밀번호는 증거에 남기지 않는다
  for f in api streamer pg-sim; do
    [ -f "$WORK/$f.log" ] && sed -E 's/(Using generated security password: ).*/\1[가림]/' "$WORK/$f.log" > "$OUT/logs/$f.log" || true
  done
}

cleanup() {
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
  docker rm -f "${CONTAINERS[@]}" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}

on_exit() {
  local code=$?
  if [ $code -ne 0 ]; then
    copy_logs
    log "실패(exit $code) — 조사용으로 자원을 남깁니다. 작업 폴더 $WORK , 결과 $OUT"
    log "지우려면: kill ${PIDS[*]:-} ; docker rm -f ${CONTAINERS[*]} ; docker network rm $NET"
  fi
}
trap on_exit EXIT

# check 이름 기대 실제 — 같으면 통과, 다르면 기록하고 멈춘다 (plan.md D-1 검사 규칙)
check() {
  local name="$1" expected="$2" actual="$3"
  CHECK_TOTAL=$((CHECK_TOTAL + 1))
  if [ "$expected" = "$actual" ]; then
    printf '%s\t%s\t%s\tPASS\n' "$name" "$expected" "$actual" >> "$CHECKS"
    log "✓ $name = $actual"
  else
    printf '%s\t%s\t%s\tFAIL\n' "$name" "$expected" "$actual" >> "$CHECKS"
    echo "실패: $name — 기대 $expected, 실제 $actual (검사 ${CHECK_TOTAL}번째)" > "$OUT/summary.txt"
    log "✗ $name — 기대 $expected, 실제 $actual"
    exit 1
  fi
}

# check_at_least 이름 최소 실제 — 숫자 실제 ≥ 최소
check_at_least() {
  local name="$1" min="$2" actual="$3"
  if [ -n "$actual" ] && [ "$actual" -ge "$min" ] 2>/dev/null; then check "$name" "≥$min" "≥$min"; else check "$name" "≥$min" "$actual"; fi
}

wait_until() { # 설명 · 최대 초 · 명령
  local what="$1" max="$2"; shift 2
  for _ in $(seq 1 "$max"); do if "$@" >/dev/null 2>&1; then log "$what"; return 0; fi; sleep 1; done
  log "시간 초과: $what"; return 1
}

sql() { docker exec -i "$RUN-mysql" mysql -uapplication -papplication -N -B --default-character-set=utf8mb4 "$DB" 2>/dev/null; }
sql1() { echo "$1" | sql | head -1; }

# 대사 질의(plan.md D-8)를 runbook과 같은 파일로 돌려 "항목<TAB>값"으로 남긴다
reconcile_check() { # 출력 파일
  { echo "SET @fault_ids = '$(IFS=,; echo "${FAULT_IDS[*]:-0}")'; SET @product_id = $PRODUCT_ID;"; cat "$W10/sql/reconcile-check.sql"; } | sql > "$1"
}
val() { awk -F'\t' -v k="$2" '$1 == k { print $2 }' "$1"; }

api_get() { curl -s -H "X-USER-ID: $USER_ID" "$API$1"; }
api_post() { curl -s -H "X-USER-ID: $USER_ID" -H 'Content-Type: application/json' "$@"; }
pg_stats() { curl -s "$PG/test/stats"; }
provider_order_id() { printf 'LP-ORD-%010d' "$1"; }

create_order() {
  api_post -X POST "$API/api/v1/orders" -d "{\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}]}" | jq -r '.data.id'
}

confirm_order() { # 주문 ID → HTTP 상태
  api_post -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/orders/$1/confirm" \
    -H "Idempotency-Key: w10-$RUN-$1" -d "{\"cardType\":\"SAMSUNG\",\"cardNo\":\"$CARD_NO\"}"
}

gauge() { # 업무 지표 파일 · 상태
  grep '^commerce_payment_unresolved{' "$1" | grep "status=\"$2\"" | awk '{ print $NF }'
}

# 단계 증거: health · 업무 지표 원문 · 대사 · 미종결 목록 · PG 상태·거래 (plan.md D-9). 읽기만 한다.
stage_evidence() { # 폴더
  local dir="$OUT/$1"
  curl -s "$MGMT/actuator/health" > "$dir/health.json"
  curl -s "$MGMT/actuator/prometheus" > "$dir/prometheus.txt"
  reconcile_check "$dir/db.tsv"
  sql < "$W10/sql/unresolved-payments.sql" > "$dir/unresolved.tsv"
  pg_stats | jq 'del(.traceparents)' > "$dir/pg-stats.json"
  curl -s "$PG/test/transactions" > "$dir/pg-transactions.json"
  date -u +%Y-%m-%dT%H:%M:%SZ > "$dir/observed-at.txt"
}

# 같은 DB·같은 PG 거래 저장소의 근거 (plan.md D-9)
check_same_resources() { # 단계
  check "$1: 같은 MySQL 컨테이너" "$MYSQL_CONTAINER_ID" "$(docker inspect -f '{{.Id}}' "$RUN-mysql")"
  check "$1: 같은 pg-sim 프로세스" "$PG_SIM_IDENTITY" "$(pg_stats | jq -r '"\(.pid)@\(.startedAt)"')"
}

trace_id_of() { # 로그 줄 → [traceId-spanId]의 traceId
  grep -oE '\[[0-9a-f]{32}-[0-9a-f]{16}\]' <<< "$1" | head -1 | cut -c2-33
}

# traceId · 저장 파일 · 기다릴 조건(요약 JSON에 대한 jq). span은 몇 초씩 묶여 Tempo로 가서, 먼저 끝난 span만 와 있을 수 있다
# — trace가 보인다고 바로 읽지 않고 기다린 span이 들어올 때까지 다시 받는다. 요약은 같은 이름의 .summary.json
fetch_trace() {
  local summary="${2%.json}.summary.json"
  for _ in $(seq 1 40); do
    if curl -sf "$TEMPO/api/traces/$1" -o "$2" && [ -s "$2" ]; then
      python3 -I "$W10/trace-summary.py" "$2" > "$summary"
      if jq -e "$3" "$summary" >/dev/null 2>&1; then return 0; fi
    fi
    sleep 1
  done
  log "trace $1: 기다린 span이 오지 않았다 — 아래 검사가 받은 그대로를 남긴다"
  return 0
}
PG_SPAN='[.spans[] | select(.kind == "SPAN_KIND_CLIENT" and (.url | test("/api/v1/payments")))] | length > 0'
KAFKA_SPANS='(.services | index("commerce-api")) and (.services | index("commerce-streamer")) and ([.spans[] | select(.kind == "SPAN_KIND_SERVER" and (.name | test("reconcile")))] | length > 0)'

# ---------------------------------------------------------------- 준비
# 주가 바뀌는 순간(월요일 00:00 UTC)에 걸리면 확정 시각이 두 주로 갈린다 — 그 근처면 실행하지 않는다 (W9와 같음)
if [ "$(date -u +%u)" = "1" ] && [ "$(date -u +%H%M)" -lt "0015" ]; then log "월요일 00:15 UTC 이후에 실행하세요"; exit 1; fi
if [ "$(date -u +%u)" = "7" ] && [ "$(date -u +%H%M)" -gt "2330" ]; then log "월요일 00:15 UTC 이후에 실행하세요"; exit 1; fi

for port in $MYSQL_PORT $REDIS_PORT "${KAFKA_PORTS[@]}" $API_PORT $API_MGMT_PORT $TEMPO_HTTP_PORT $TEMPO_OTLP_PORT $PROMETHEUS_PORT $GRAFANA_PORT $PG_PORT; do
  if lsof -iTCP:"$port" -sTCP:LISTEN -n -P >/dev/null 2>&1; then log "포트 ${port}가 이미 쓰이고 있습니다"; exit 1; fi
done
log "실행 $RUN · 작업 폴더 $WORK"

(cd "$REPO" && ./gradlew :apps:commerce-api:bootJar :apps:commerce-streamer:bootJar :apps:commerce-batch:bootJar -q)
# jar 이름은 <앱>-<git short hash>.jar (build.gradle.kts getGitHash) — 예전 커밋의 jar가 남아 있으니 지금 HEAD 것을 이름으로 고른다
jar_of() {
  local jar="$REPO/apps/$1/build/libs/$1-$(git -C "$REPO" rev-parse --short HEAD).jar"
  [ -f "$jar" ] || { log "jar가 없습니다: $jar"; exit 1; }
  echo "$jar"
}
API_JAR="$(jar_of commerce-api)"; STREAMER_JAR="$(jar_of commerce-streamer)"; BATCH_JAR="$(jar_of commerce-batch)"

docker network create "$NET" >/dev/null
docker run -d --name "$RUN-mysql" --network "$NET" -p "127.0.0.1:$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=$DB -e MYSQL_USER=application -e MYSQL_PASSWORD=application \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci >/dev/null
docker run -d --name "$RUN-redis" --network "$NET" -p "127.0.0.1:$REDIS_PORT:6379" redis:7.4.7 >/dev/null
VOTERS="1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093"
for id in 1 2 3; do
  port="${KAFKA_PORTS[$((id-1))]}"
  docker run -d --name "$RUN-kafka-$id" --network "$NET" --network-alias "kafka-$id" -p "127.0.0.1:$port:19092" \
    -e CLUSTER_ID=bG9vcGVyc3cxMGthZrYaAA -e KAFKA_NODE_ID=$id -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS="$VOTERS" \
    -e KAFKA_LISTENERS="PLAINTEXT://:9092,CONTROLLER://:9093,PLAINTEXT_HOST://:19092" \
    -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://kafka-$id:9092,PLAINTEXT_HOST://localhost:$port" \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP="PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT" \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false -e KAFKA_DEFAULT_REPLICATION_FACTOR=3 -e KAFKA_MIN_INSYNC_REPLICAS=2 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=3 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=3 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=2 -e KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE=false \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 apache/kafka:3.8.1 >/dev/null
done
docker run -d --name "$RUN-tempo" --network "$NET" --network-alias tempo \
  -p "127.0.0.1:$TEMPO_HTTP_PORT:3200" -p "127.0.0.1:$TEMPO_OTLP_PORT:4318" \
  -v "$W10/tempo.yaml:/etc/tempo.yaml:ro" grafana/tempo:2.7.2 -config.file=/etc/tempo.yaml >/dev/null
cat > "$WORK/prometheus.yml" <<EOF
global:
  scrape_interval: 5s
scrape_configs:
  - job_name: commerce-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:$API_MGMT_PORT']
EOF
docker run -d --name "$RUN-prometheus" --network "$NET" --network-alias prometheus -p "127.0.0.1:$PROMETHEUS_PORT:9090" \
  -v "$WORK/prometheus.yml:/etc/prometheus/prometheus.yml:ro" prom/prometheus:v3.1.0 >/dev/null
docker run -d --name "$RUN-grafana" --network "$NET" -p "127.0.0.1:$GRAFANA_PORT:3000" \
  -e GF_AUTH_ANONYMOUS_ENABLED=true -e GF_AUTH_ANONYMOUS_ORG_ROLE=Admin -e GF_AUTH_DISABLE_LOGIN_FORM=true \
  -v "$W10/grafana:/etc/grafana/provisioning/datasources:ro" grafana/grafana:11.4.0 >/dev/null

wait_until "MySQL 준비" 120 docker exec "$RUN-mysql" mysql -uapplication -papplication -e "SELECT 1" $DB
# 엔티티가 아니거나 운영처럼 DDL 파일로 관리하는 테이블 — local 프로필은 ddl-auto: none이다 (나머지는 아래 ddl-auto=update)
for ddl in docs/week7/schema.sql docs/week9/schema.sql docs/week10/schema.sql; do
  docker exec -i "$RUN-mysql" mysql -uapplication -papplication --default-character-set=utf8mb4 $DB < "$REPO/$ddl" 2>/dev/null
done
log "DDL 적용: week7(order_outbox · shedlock) · week9(랭킹 4개) · week10(order_outbox.trace_parent)"
for id in 1 2 3; do wait_until "Kafka $id 준비" 120 sh -c "docker logs $RUN-kafka-$id 2>&1 | grep -q 'Kafka Server started'"; done
for topic in order-events coupon-issue-requests; do
  docker exec "$RUN-kafka-1" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --create --topic "$topic" \
    --partitions 3 --replication-factor 3 --config min.insync.replicas=2 >/dev/null
done
wait_until "Tempo 준비" 90 sh -c "curl -sf $TEMPO/ready | grep -q ready"

python3 -I "$W10/pg-sim.py" > "$WORK/pg-sim.log" 2>&1 &
PIDS+=($!)
wait_until "pg-sim 준비" 15 curl -sf "$PG/test/stats"

BOOTSTRAP="localhost:${KAFKA_PORTS[0]},localhost:${KAFKA_PORTS[1]},localhost:${KAFKA_PORTS[2]}"
JDBC="jdbc:mysql://localhost:$MYSQL_PORT/$DB"
COMMON_ARGS=(
  --spring.profiles.active=local
  "--spring.config.additional-location=file:$W10/run.yml"
  "--datasource.mysql-jpa.main.jdbc-url=$JDBC"
  "--spring.kafka.bootstrap-servers=$BOOTSTRAP"
  --datasource.redis.master.host=localhost "--datasource.redis.master.port=$REDIS_PORT"
  # 목록 항목은 명령줄에서 통째로 바뀐다 — host도 함께 준다 (replica = 같은 Redis 한 대)
  "--datasource.redis.replicas[0].host=localhost" "--datasource.redis.replicas[0].port=$REDIS_PORT"
  --spring.jpa.hibernate.ddl-auto=update
  "--management.otlp.tracing.endpoint=http://localhost:$TEMPO_OTLP_PORT/v1/traces"
)
java -jar "$API_JAR" "${COMMON_ARGS[@]}" \
  --server.port=$API_PORT --management.server.port=$API_MGMT_PORT \
  "--pg.local.callback-url=http://localhost:$API_PORT/api/v1/payments/callback" > "$WORK/api.log" 2>&1 &
PIDS+=($!)
wait_until "commerce-api 준비" 120 sh -c "curl -sf $MGMT/actuator/health | grep -q '\"UP\"'"
sql < "$W10/sql/seed.sql"
log "준비 데이터: 사용자 $USER_ID · 상품 $PRODUCT_ID (재고 $(sql1 "SELECT stock FROM products WHERE id = $PRODUCT_ID"))"

java -jar "$STREAMER_JAR" "${COMMON_ARGS[@]}" --server.port=0 --management.server.port=0 > "$WORK/streamer.log" 2>&1 &
PIDS+=($!)
wait_until "streamer가 order-events partition을 받음" 120 sh -c "grep -q 'partitions assigned: \[.*order-events' '$WORK/streamer.log'"

MYSQL_CONTAINER_ID="$(docker inspect -f '{{.Id}}' "$RUN-mysql")"
PG_SIM_IDENTITY="$(pg_stats | jq -r '"\(.pid)@\(.startedAt)"')"
{
  echo "run=$RUN"
  echo "startedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "gitCommit=$(git -C "$REPO" rev-parse HEAD)$(git -C "$REPO" diff --quiet HEAD -- apps modules supports docs/week10/pg-sim.py || echo '+dirty')"
  echo "pgSimSha256=$(shasum -a 256 "$W10/pg-sim.py" | cut -d' ' -f1)"
  echo "jars=$(basename "$API_JAR") $(basename "$STREAMER_JAR") $(basename "$BATCH_JAR")"
  echo "pgSim=$PG_SIM_IDENTITY"
  echo "mysqlContainer=$MYSQL_CONTAINER_ID"
  echo "ports: api=$API_PORT management=$API_MGMT_PORT mysql=$MYSQL_PORT tempo=$TEMPO_HTTP_PORT grafana=$GRAFANA_PORT"
} > "$OUT/run.txt"

# ---------------------------------------------------------------- BASELINE
log "── BASELINE: 응답 유실 꺼짐 · 정상 집합 10건"
check "baseline: 응답 유실 꺼짐" "[]" "$(pg_stats | jq -c .drop)"
BASELINE_IDS=()
for _ in $(seq 1 10); do BASELINE_IDS+=("$(create_order)"); done
printf '%s\n' "${BASELINE_IDS[@]}" > "$OUT/ids/baseline-orders.txt"
for id in "${BASELINE_IDS[@]}"; do check "baseline: 주문 $id 확정 요청 접수" "202" "$(confirm_order "$id")"; done
BASELINE_CSV="$(IFS=,; echo "${BASELINE_IDS[*]}")"
wait_until "정상 집합 10건 확정" 60 sh -c "[ \"\$(echo \"SELECT COUNT(*) FROM orders WHERE id IN ($BASELINE_CSV) AND status = 'CONFIRMED'\" | docker exec -i $RUN-mysql mysql -uapplication -papplication -N -B $DB 2>/dev/null)\" = 10 ]"
FAULT_IDS=()
stage_evidence 1-baseline
B="$OUT/1-baseline"
check_same_resources "baseline"
check "baseline: health" "UP" "$(jq -r .status "$B/health.json")"
check "baseline: 업무 지표 UNKNOWN" "0.0" "$(gauge "$B/prometheus.txt" UNKNOWN)"
check "baseline: 업무 지표 PENDING" "0.0" "$(gauge "$B/prometheus.txt" PENDING)"
check "baseline: 상품 재고" "90" "$(val "$B/db.tsv" product.stock)"
check "baseline: 확정 주문" "10" "$(val "$B/db.tsv" all.order.CONFIRMED)"
check "baseline: 선점 CONSUMED" "10" "$(val "$B/db.tsv" all.reservation.CONSUMED)"
check "baseline: PG 거래 수" "10" "$(jq -r .transactions "$B/pg-stats.json")"
check "baseline: PG create 호출 수" "10" "$(jq -r .create "$B/pg-stats.json")"

# ---------------------------------------------------------------- FAULT
log "── FAULT: 장애 집합 10건, 2·5·8번째 응답 유실"
for _ in $(seq 1 10); do FAULT_IDS+=("$(create_order)"); done
LOST_IDS=("${FAULT_IDS[1]}" "${FAULT_IDS[4]}" "${FAULT_IDS[7]}")
printf '%s\n' "${FAULT_IDS[@]}" > "$OUT/ids/fault-orders.txt"
printf '%s\n' "${LOST_IDS[@]}" > "$OUT/ids/lost-orders.txt"
LOST_PG_IDS_JSON="$(for id in "${LOST_IDS[@]}"; do provider_order_id "$id"; echo; done | jq -R . | jq -sc .)"
curl -s -H 'Content-Type: application/json' -d "{\"orderIds\":$LOST_PG_IDS_JSON}" "$PG/test/drop" > /dev/null
check "fault: 응답 유실 목록 = 지정한 3건" "$LOST_PG_IDS_JSON" "$(pg_stats | jq -c .drop)"
for id in "${FAULT_IDS[@]}"; do check "fault: 주문 $id 확정 요청 접수" "202" "$(confirm_order "$id")"; done
FAULT_CSV="$(IFS=,; echo "${FAULT_IDS[*]}")"
wait_until "장애 집합: 확정 7 · 결제 UNKNOWN 3" 60 sh -c "[ \"\$(echo \"SELECT CONCAT(SUM(o.status = 'CONFIRMED'), '/', SUM(p.status = 'UNKNOWN')) FROM orders o JOIN payments p ON p.order_id = o.id WHERE o.id IN ($FAULT_CSV)\" | docker exec -i $RUN-mysql mysql -uapplication -papplication -N -B $DB 2>/dev/null)\" = 7/3 ]"
sleep 3   # pg-sim의 늦은 응답(5초)까지 끝나게 둔다 — 로그가 다 쓰인 상태를 증거로 남긴다

log "── 증거 보존 (읽기만 — reconcile 전)"
stage_evidence 2-fault
F="$OUT/2-fault"
for id in "${LOST_IDS[@]}"; do
  # 진단 ③: PG 조회 API를 직접 부른다 (commerce-api를 거치지 않음, plan.md D-6). 계정값은 파일에 남기지 않는다
  curl -s -H "X-USER-ID: $PG_ACCOUNT" "$PG/api/v1/payments?orderId=$(provider_order_id "$id")" > "$F/pg-lookup-$id.json"
  # 진단 ④: 경계 로그 — 주문 ID로 grep (orderId=12가 orderId=120에 걸리지 않게)
  grep -E "orderId=$id[^0-9]" "$WORK/api.log" >> "$F/lost-orders-logs.txt" || true
done
check_same_resources "fault"
check "fault: health (사고 중에도)" "UP" "$(jq -r .status "$F/health.json")"
check "fault: 업무 지표 UNKNOWN" "3.0" "$(gauge "$F/prometheus.txt" UNKNOWN)"
check "fault: 장애 집합 결제 SUCCESS" "7" "$(val "$F/db.tsv" fault.payment.SUCCESS)"
check "fault: 장애 집합 결제 UNKNOWN" "3" "$(val "$F/db.tsv" fault.payment.UNKNOWN)"
check "fault: 장애 집합 거래 키 NULL" "3" "$(val "$F/db.tsv" fault.payment.transaction_key_null)"
check "fault: 장애 집합 PG 거래 SUCCESS" "10" "$(jq --argjson ids "$(printf '%s\n' "${FAULT_IDS[@]}" | while read -r i; do provider_order_id "$i"; echo; done | jq -R . | jq -sc .)" '[.[] | select(.orderId as $o | $ids | index($o)) | select(.status == "SUCCESS")] | length' "$F/pg-transactions.json")"
check "fault: 상품 재고" "80" "$(val "$F/db.tsv" product.stock)"
check "fault: 선점 RESERVED" "3" "$(val "$F/db.tsv" all.reservation.RESERVED)"
check "fault: 선점 CONSUMED" "17" "$(val "$F/db.tsv" all.reservation.CONSUMED)"
check "fault: 확정 주문" "17" "$(val "$F/db.tsv" all.order.CONFIRMED)"
check "fault: PG create 호출 수" "20" "$(jq -r .create "$F/pg-stats.json")"
check "fault: 중복 create" "{}" "$(jq -c .duplicates "$F/pg-stats.json")"
check "fault: 보관한 callback" "3" "$(jq -r .callback.held "$F/pg-stats.json")"
check "fault: 미종결 결제 목록 줄 수" "3" "$(wc -l < "$F/unresolved.tsv" | tr -d ' ')"

# 가설 판정 (plan.md D-6): 예상과 실제를 나란히 — 실제 값은 로그·PG 조회·trace에서 센다
H_PG_SUCCESS=0; H_READ_TIMEOUT=0; H_ACCEPTED=0; H_APPLY_FAILED=0; H_SLOW_SPAN=0
for id in "${LOST_IDS[@]}"; do
  amount="$(sql1 "SELECT amount FROM payments WHERE order_id = $id")"
  check "fault: 주문 $id PG 조회 = SUCCESS 1건 · 금액 $amount" "1/SUCCESS/$amount" "$(jq -r '"\(length)/\(.[0].status)/\(.[0].amount)"' "$F/pg-lookup-$id.json")"
  [ "$(jq -r '.[0].status' "$F/pg-lookup-$id.json")" = SUCCESS ] && H_PG_SUCCESS=$((H_PG_SUCCESS + 1))
  create_line="$(grep -E "payment\.create\.result orderId=$id " "$WORK/api.log" | head -1)"
  check "fault: 주문 $id 로그 ② 결과" "result=UNKNOWN cause=READ_TIMEOUT" "$(grep -oE 'result=[A-Z_]+ cause=[A-Z_:a-zA-Z]+' <<< "$create_line")"
  check_at_least "fault: 주문 $id 로그 ② 기다린 ms" 2900 "$(grep -oE 'elapsedMs=[0-9]+' <<< "$create_line" | cut -d= -f2)"
  grep -q 'cause=READ_TIMEOUT' <<< "$create_line" && H_READ_TIMEOUT=$((H_READ_TIMEOUT + 1))
  grep -q 'result=ACCEPTED' <<< "$create_line" && H_ACCEPTED=$((H_ACCEPTED + 1))
  failed="$(grep -cE "payment\.apply\.failed orderId=$id " "$WORK/api.log" || true)"
  check "fault: 주문 $id 로그 ⑤ 반영 실패 줄" "0" "$failed"
  H_APPLY_FAILED=$((H_APPLY_FAILED + failed))

  # 추적 (plan.md D-12): ② 줄의 traceId → Tempo → PG 호출 span
  trace_id="$(trace_id_of "$create_line")"
  check "fault: 주문 $id 로그 ②에 traceId" "32" "${#trace_id}"
  fetch_trace "$trace_id" "$F/traces/$trace_id.json" "$PG_SPAN"
  pg_span="$(jq -c '[.spans[] | select(.kind == "SPAN_KIND_CLIENT" and (.url | test("/api/v1/payments")))] | max_by(.durationMs)' "$F/traces/$trace_id.summary.json")"
  check_at_least "fault: 주문 $id trace PG 호출 span ms" 2900 "$(jq -r '.durationMs // 0' <<< "$pg_span")"
  check "fault: 주문 $id trace PG 호출 span 오류" "true" "$(jq -r '.error' <<< "$pg_span")"
  check "fault: 주문 $id trace에 확정 요청 span" "true" "$(jq -r '[.spans[] | select(.kind == "SPAN_KIND_SERVER" and (.name | test("confirm")))] | length > 0' "$F/traces/$trace_id.summary.json")"
  check "fault: 주문 $id pg-sim이 받은 traceparent의 traceId" "$trace_id" "$(pg_stats | jq -r --arg o "$(provider_order_id "$id")" '.traceparents[$o][0] | split("-")[1]')"
  [ "$(jq -r '.durationMs // 0' <<< "$pg_span")" -ge 2900 ] && H_SLOW_SPAN=$((H_SLOW_SPAN + 1))
done
verdict() { if [ "$1" = yes ]; then echo 지지; elif [ "$1" = no ]; then echo 기각; else echo 미확정; fi; }
{
  printf '가설\t예상\t실제\t판정\n'
  printf '1. PG가 아직 처리 중\tPG 조회 PENDING\tPG 조회 SUCCESS %s/3\t%s\n' "$H_PG_SUCCESS" "$([ $H_PG_SUCCESS = 3 ] && verdict no || verdict unknown)"
  printf '2. PG 성공 응답을 잃음\t② UNKNOWN cause=READ_TIMEOUT · ⑤ 없음 · PG 호출 span ≥ 3초\t② READ_TIMEOUT %s/3 · ⑤ %s줄 · 느린 PG span %s/3\t%s\n' \
    "$H_READ_TIMEOUT" "$H_APPLY_FAILED" "$H_SLOW_SPAN" "$([ $H_READ_TIMEOUT = 3 ] && [ $H_APPLY_FAILED = 0 ] && [ $H_SLOW_SPAN = 3 ] && verdict yes || verdict unknown)"
  printf '3. 성공 조회 뒤 내부 반영 실패\t② ACCEPTED · ⑤ 있음\t② ACCEPTED %s/3 · ⑤ %s줄\t%s\n' \
    "$H_ACCEPTED" "$H_APPLY_FAILED" "$([ $H_ACCEPTED = 0 ] && [ $H_APPLY_FAILED = 0 ] && verdict no || verdict unknown)"
} > "$F/hypotheses.tsv"
check "fault: 가설 판정 (1 기각 · 2 지지 · 3 기각)" "기각/지지/기각" "$(awk -F'\t' 'NR > 1 { printf "%s%s", (NR > 2 ? "/" : ""), $4 }' "$F/hypotheses.tsv")"
(cd "$OUT" && find 2-fault -type f | sort | xargs shasum -a 256 > fault.sha256)
log "fault 증거 sha256 기록 ($(wc -l < "$OUT/fault.sha256" | tr -d ' ')개 파일)"

# ---------------------------------------------------------------- RECOVERY
log "── RECOVERY: 응답 유실 해제 → 같은 3건에 고객 reconcile API"
curl -s -H 'Content-Type: application/json' -d '{"orderIds":[]}' "$PG/test/drop" > /dev/null
check "recovery: 응답 유실 꺼짐" "[]" "$(pg_stats | jq -c .drop)"
CREATES_BEFORE="$(pg_stats | jq -r .create)"
R="$OUT/3-recovery"
for id in "${LOST_IDS[@]}"; do
  code="$(api_post -o "$R/reconcile-$id.json" -w '%{http_code}' -X POST "$API/api/v1/orders/$id/payment/reconcile")"
  check "recovery: 주문 $id reconcile 응답" "200/SUCCESS/CONFIRMED" "$code/$(jq -r '"\(.data.paymentStatus)/\(.data.orderStatus)"' "$R/reconcile-$id.json")"
  check "recovery: 주문 $id 뒤 PG create 호출 수" "$CREATES_BEFORE" "$(pg_stats | jq -r .create)"
done
wait_until "장애 집합 Outbox 발행 10 · 구매 활동 10" 60 sh -c "[ \"\$(echo \"SELECT CONCAT((SELECT COUNT(*) FROM order_outbox WHERE order_id IN ($FAULT_CSV) AND status = 'PUBLISHED'), '/', (SELECT COUNT(*) FROM product_purchase_activity WHERE order_id IN ($FAULT_CSV)))\" | docker exec -i $RUN-mysql mysql -uapplication -papplication -N -B $DB 2>/dev/null)\" = 10/10 ]"
reconcile_check "$WORK/before-repeat.tsv"
log "── 반복: 같은 3건 reconcile 한 번 더 + 보관한 callback 전송"
for id in "${LOST_IDS[@]}"; do
  code="$(api_post -o "$R/reconcile-again-$id.json" -w '%{http_code}' -X POST "$API/api/v1/orders/$id/payment/reconcile")"
  check "recovery: 주문 $id 다시 reconcile 응답" "200/SUCCESS" "$code/$(jq -r '.data.paymentStatus' "$R/reconcile-again-$id.json")"
done
curl -s -X POST "$PG/test/callbacks/release" > "$R/callbacks-release.json"
check "recovery: 보관 callback 전송" "3/0" "$(jq -r '"\(.sent)/\(.failed)"' "$R/callbacks-release.json")"
sleep 5   # callback 처리·relay 한 바퀴를 기다린다 — 늘어날 것이 있으면 이 사이에 는다
stage_evidence 3-recovery
for item in fault.outbox.rows fault.activity.rows all.reservation.CONSUMED all.order.CONFIRMED product.stock all.outbox.rows all.activity.rows; do
  check "recovery: 반복 뒤 $item 그대로" "$(val "$WORK/before-repeat.tsv" "$item")" "$(val "$R/db.tsv" "$item")"
done
check_same_resources "recovery"
check "recovery: health" "UP" "$(jq -r .status "$R/health.json")"
check "recovery: 업무 지표 UNKNOWN" "0.0" "$(gauge "$R/prometheus.txt" UNKNOWN)"
check "recovery: 장애 집합 결제 SUCCESS" "10" "$(val "$R/db.tsv" fault.payment.SUCCESS)"
check "recovery: 장애 집합 결제 UNKNOWN" "0" "$(val "$R/db.tsv" fault.payment.UNKNOWN)"
check "recovery: 장애 집합 거래 키 NULL" "0" "$(val "$R/db.tsv" fault.payment.transaction_key_null)"
pg_keys() { jq -r --argjson ids "$(printf '%s\n' "${FAULT_IDS[@]}" | while read -r i; do provider_order_id "$i"; echo; done | jq -R . | jq -sc .)" \
  '[.[] | select(.orderId as $o | $ids | index($o)) | "\(.orderId) \(.transactionKey)"] | sort | join(",")' "$1"; }
check "recovery: 장애 집합 PG 거래 = fault 때와 같은 10개" "$(pg_keys "$F/pg-transactions.json")" "$(pg_keys "$R/pg-transactions.json")"
db_keys="$(sql1 "SELECT GROUP_CONCAT(CONCAT(provider_order_id, ' ', transaction_key) ORDER BY provider_order_id SEPARATOR ',') FROM payments WHERE order_id IN ($FAULT_CSV)")"
check "recovery: DB 거래 키 = PG 거래 키" "$(pg_keys "$R/pg-transactions.json")" "$db_keys"
check "recovery: 금액 불일치" "0" "$(val "$R/db.tsv" fault.amount_mismatch)"
check "recovery: PG create 호출 수" "20" "$(jq -r .create "$R/pg-stats.json")"
check "recovery: 중복 create" "{}" "$(jq -c .duplicates "$R/pg-stats.json")"
check "recovery: PG 거래 수" "20" "$(jq -r .transactions "$R/pg-stats.json")"
check "recovery: 상품 재고 (79면 이중 차감)" "80" "$(val "$R/db.tsv" product.stock)"
check "recovery: 장애 집합 선점 RESERVED" "0" "$(val "$R/db.tsv" fault.reservation.RESERVED)"
check "recovery: 장애 집합 선점 CONSUMED" "10" "$(val "$R/db.tsv" fault.reservation.CONSUMED)"
check "recovery: 포인트 기록 (fault 때와 같음)" "$(val "$F/db.tsv" point_histories.rows)" "$(val "$R/db.tsv" point_histories.rows)"
check "recovery: 쿠폰 보유 (fault 때와 같음)" "$(val "$F/db.tsv" user_coupon.rows)" "$(val "$R/db.tsv" user_coupon.rows)"
check "recovery: 장애 집합 Outbox 줄" "10" "$(val "$R/db.tsv" fault.outbox.rows)"
check "recovery: 장애 집합 Outbox 발행됨" "10" "$(val "$R/db.tsv" fault.outbox.published)"
check "recovery: 주문당 Outbox 줄 최대" "1" "$(val "$R/db.tsv" fault.outbox.max_rows_per_order)"
check "recovery: 장애 집합 구매 활동 줄" "10" "$(val "$R/db.tsv" fault.activity.rows)"
check "recovery: 주문당 구매 활동 줄 최대" "1" "$(val "$R/db.tsv" fault.activity.max_rows_per_order)"
check "recovery: 장애 집합 구매 수량 합" "10" "$(val "$R/db.tsv" fault.activity.quantity_sum)"
check "recovery: 구매 활동 확정 시각 = 주문 확정 시각" "0" "$(val "$R/db.tsv" fault.activity.confirmed_at_mismatch)"
check "recovery: 확정 시각 빈 주문" "0" "$(val "$R/db.tsv" fault.order.confirmed_at_null)"
check "recovery: 확정 주문이 걸친 주 수" "1" "$(val "$R/db.tsv" all.confirmed_weeks)"
check "recovery: 미종결 결제 목록 줄 수" "0" "$(wc -l < "$R/unresolved.tsv" | tr -d ' ')"
for id in "${LOST_IDS[@]}"; do
  check "recovery: 주문 $id 로그 ④ reconcile API로 SUCCESS" "1" \
    "$(grep -cE "payment\.apply orderId=$id .*from=UNKNOWN to=SUCCESS outcome=APPLIED source=RECONCILE_API" "$WORK/api.log" || true)"
done
# Kafka 구간 trace (plan.md D-12): streamer의 "구매 활동 저장" 줄 traceId → Tempo에 commerce-api(발행)·commerce-streamer(소비)가 함께 있다
saved_line="$(grep -E "구매 활동 저장\..*orderId=${LOST_IDS[0]}[^0-9]" "$WORK/streamer.log" | head -1)"
kafka_trace="$(trace_id_of "$saved_line")"
check "recovery: 구매 활동 저장 로그에 traceId" "32" "${#kafka_trace}"
fetch_trace "$kafka_trace" "$R/traces/$kafka_trace.json" "$KAFKA_SPANS"
check "recovery: 발행·소비가 같은 trace" "commerce-api,commerce-streamer" "$(jq -r '.services | join(",")' "$R/traces/$kafka_trace.summary.json")"
# (나) Outbox에 남긴 trace 문맥: 결과를 반영한 reconcile 요청 → relay 발행 → 구매 활동 소비가 한 trace (plan.md D-12 (나))
check "recovery: 응답 유실 주문 Outbox에 trace 문맥" "3" "$(sql1 "SELECT COUNT(*) FROM order_outbox WHERE order_id IN ($(IFS=,; echo "${LOST_IDS[*]}")) AND trace_parent IS NOT NULL")"
apply_line="$(grep -E "payment\.apply orderId=${LOST_IDS[0]} .*source=RECONCILE_API" "$WORK/api.log" | head -1)"
check "recovery: reconcile 요청 trace = 구매 활동 소비 trace" "$(trace_id_of "$apply_line")" "$kafka_trace"
check "recovery: 그 trace에 reconcile 요청 span" "true" "$(jq -r '[.spans[] | select(.kind == "SPAN_KIND_SERVER" and (.name | test("reconcile")))] | length > 0' "$R/traces/$kafka_trace.summary.json")"

# ---------------------------------------------------------------- RANKING
log "── RANKING: batch jar → 새 snapshot → 랭킹 API"
PERIOD_START="$(sql1 "SELECT DATE_FORMAT(DATE_SUB(DATE(MIN(confirmed_at)), INTERVAL WEEKDAY(MIN(confirmed_at)) DAY), '%Y-%m-%d') FROM orders WHERE confirmed_at IS NOT NULL")"
set +e
java -jar "$BATCH_JAR" --spring.profiles.active=local "--spring.config.additional-location=file:$W10/run.yml" \
  "--datasource.mysql-jpa.main.jdbc-url=$JDBC" --job.name=weeklyRankingJob "periodStart=$PERIOD_START" > "$OUT/4-ranking/batch.log" 2>&1
BATCH_EXIT=$?
set -e
check "ranking: batch 종료 코드 (periodStart=$PERIOD_START)" "0" "$BATCH_EXIT"
check "ranking: 새 snapshot 입력 줄·수량 합" "20/20" "$(sql1 "SELECT CONCAT(input_count, '/', input_quantity_sum) FROM ranking_snapshot WHERE period_start = '$PERIOD_START' ORDER BY revision DESC LIMIT 1")"
api_get "/api/v1/rankings/weekly?periodStart=$PERIOD_START" > "$OUT/4-ranking/ranking.json"
check "ranking: 랭킹 API 1위 상품·점수" "1/$PRODUCT_ID/20" "$(jq -r '.data.items[0] | "\(.rank)/\(.productId)/\(.score)"' "$OUT/4-ranking/ranking.json")"

# ---------------------------------------------------------------- 끝
check "끝: fault 증거를 덮어쓰지 않음" "OK" "$(cd "$OUT" && shasum -a 256 -c fault.sha256 >/dev/null 2>&1 && echo OK || echo CHANGED)"
copy_logs
check "끝: 카드 번호가 증거·로그에 없음" "0" "$(grep -rF -- "$CARD_NO" "$OUT" | wc -l | tr -d ' ')"
PROM_TARGET="$(curl -s "http://127.0.0.1:$PROMETHEUS_PORT/api/v1/targets" | jq -r '.data.activeTargets[0].health // "unknown"')"
echo "finishedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$OUT/run.txt"
{
  echo "검사 ${CHECK_TOTAL}개 중 ${CHECK_TOTAL}개 통과"
  echo
  echo "단계 | 재고 | 선점 RESERVED·CONSUMED | 확정 | 결제 UNKNOWN | 업무 지표 UNKNOWN | PG 거래·create | health"
  for stage in 1-baseline 2-fault 3-recovery; do
    d="$OUT/$stage"
    echo "$stage | $(val "$d/db.tsv" product.stock) | $(val "$d/db.tsv" all.reservation.RESERVED)·$(val "$d/db.tsv" all.reservation.CONSUMED) | $(val "$d/db.tsv" all.order.CONFIRMED) | $(val "$d/db.tsv" all.payment.UNKNOWN) | $(gauge "$d/prometheus.txt" UNKNOWN) | $(jq -r .transactions "$d/pg-stats.json")·$(jq -r .create "$d/pg-stats.json") | $(jq -r .status "$d/health.json")"
  done
  echo "랭킹 | periodStart=$PERIOD_START · 1위 $(jq -r '.data.items[0] | "상품 \(.productId) 점수 \(.score)"' "$OUT/4-ranking/ranking.json")"
  echo
  echo "응답 유실 주문: ${LOST_IDS[*]}"
  echo "가설 판정:"; column -t -s $'\t' "$F/hypotheses.tsv"
  echo
  echo "(참고) Prometheus 서버가 commerce-api 업무 지표를 수집한 상태: $PROM_TARGET"
} > "$OUT/summary.txt"
cat "$OUT/summary.txt"

if [ "$KEEP" = 1 ]; then
  log "KEEP=1 — 자원을 남깁니다. Grafana http://127.0.0.1:$GRAFANA_PORT (Explore → Tempo/Prometheus)"
  log "지우려면: kill ${PIDS[*]} ; docker rm -f ${CONTAINERS[*]} ; docker network rm $NET"
  trap - EXIT
else
  cleanup
  trap - EXIT
fi
log "끝 — 결과 $OUT"
