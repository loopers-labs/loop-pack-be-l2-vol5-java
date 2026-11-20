#!/usr/bin/env bash
# W9 §7 한 연결 실행 (docs/week9/plan.md D-9) — W7 docs/week7/c7/run.sh와 같은 방식으로 진짜 프로세스를 이어 붙인다.
#
#   commerce-api 주문 확정(pg-sim) → Outbox relay → 실제 Kafka 3대 → commerce-streamer(java -jar) → 구매 활동
#   → commerce-batch(java -jar, D-8 명령 그대로) → commerce-api 랭킹 API
#
# 막 순서
#   1막  주문 A(7×2, 8×1) · B(7×3) · C(9×5) 확정 → relay → streamer 저장 (api, isolatedRun)
#   batch ① 정기 명령: periodStart만 → r1 → 종료 코드 0
#   2막  랭킹 API = 7·9·8 (r1)
#   3막  주문 A의 같은 이벤트 재발행 → 구매 활동 그대로 / 같은 주 늦은 구매 D(8×10)
#   batch ② 같은 정기 명령 다시 → "이미 끝난 작업"으로 거절 (종료 코드 ≠ 0)
#   4막  랭킹 API는 여전히 r1, r1 입력 4건·합 11 그대로
#   batch ③ --recompute=true → r2 → 종료 코드 0
#   5막  랭킹 API = 8·7·9 (r2), r1 입력은 그대로
#
# 이 스크립트가 격리 자원의 수명주기를 소유한다: MySQL 1 · Kafka 3대 · pg-sim · streamer.
# 실패하면 조사할 수 있게 자원을 남기고, 지우는 명령을 출력한다.
#
# 실행: 저장소 루트에서  bash docs/week9/c7/run.sh
# 결과: docs/week9/c7/results/<실행 ID>/{facts.json, batch-*.log, act*.json, summary.txt}
set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
RUN="w9c7-$(date +%Y%m%d-%H%M%S)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/${RUN}.XXXX")"
OUT="$REPO/docs/week9/c7/results/$RUN"
MYSQL_PORT="${MYSQL_PORT:-33319}"
KAFKA_PORTS=(39192 39193 39194)
NET="$RUN-net"
PG_URL="http://127.0.0.1:8082"
mkdir -p "$OUT"

export W9_JDBC_URL="jdbc:mysql://localhost:${MYSQL_PORT}/loopers_w9"
export W9_BOOTSTRAP="localhost:${KAFKA_PORTS[0]},localhost:${KAFKA_PORTS[1]},localhost:${KAFKA_PORTS[2]}"
export W9_PG_URL="$PG_URL"
export W9_FACTS="$OUT/facts.json"
export W9_OUT="$OUT"
export W9_STREAMER_LOG="$WORK/streamer.log"

PIDS=()
cleanup() {
  echo "[w9] 정리: $RUN"
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
  docker rm -f "$RUN-mysql" "$RUN-kafka-1" "$RUN-kafka-2" "$RUN-kafka-3" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
on_exit() {
  local code=$?
  if [ $code -ne 0 ]; then
    cp "$WORK/streamer.log" "$OUT/streamer.log" 2>/dev/null || true
    echo "[w9] 실패(exit $code) — 조사용으로 자원을 남깁니다. 로그: $WORK , 결과: $OUT"
    echo "[w9] 지우려면: kill ${PIDS[*]:-} ; docker rm -f $RUN-mysql $RUN-kafka-1 $RUN-kafka-2 $RUN-kafka-3 ; docker network rm $NET"
  fi
}
trap on_exit EXIT

wait_until() { # 설명 · 최대 초 · 명령
  local what="$1" max="$2"; shift 2
  for _ in $(seq 1 "$max"); do if "$@" >/dev/null 2>&1; then echo "[w9] $what"; return 0; fi; sleep 1; done
  echo "[w9] 시간 초과: $what"; return 1
}

# 주가 바뀌는 순간(월요일 00:00 UTC)에 걸리면 주문들이 두 주로 갈린다 — 그 근처면 실행하지 않는다
if [ "$(date -u +%u)" = "1" ] && [ "$(date -u +%H%M)" -lt "0015" ]; then echo "[w9] 월요일 00:15 UTC 이후에 실행하세요"; exit 1; fi
if [ "$(date -u +%u)" = "7" ] && [ "$(date -u +%H%M)" -gt "2345" ]; then echo "[w9] 월요일 00:15 UTC 이후에 실행하세요"; exit 1; fi

echo "[w9] 실행 $RUN · 작업 폴더 $WORK"
lsof -iTCP:8082 -sTCP:LISTEN -n -P >/dev/null 2>&1 && { echo "[w9] 8082(pg-sim)가 이미 쓰이고 있습니다"; exit 1; }

# ---- jar 준비 (현재 코드) ----
(cd "$REPO" && ./gradlew :apps:commerce-streamer:bootJar :apps:commerce-batch:bootJar -q)
STREAMER_JAR="$(ls "$REPO"/apps/commerce-streamer/build/libs/*.jar | grep -v plain | head -1)"
BATCH_JAR="$(ls "$REPO"/apps/commerce-batch/build/libs/*.jar | grep -v plain | head -1)"

# ---- 격리 MySQL ----
docker network create "$NET" >/dev/null
docker run -d --name "$RUN-mysql" --network "$NET" -p "${MYSQL_PORT}:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=loopers_w9 -e MYSQL_USER=application -e MYSQL_PASSWORD=application \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci >/dev/null
wait_until "MySQL 준비" 120 docker exec "$RUN-mysql" mysql -uapplication -papplication -e "SELECT 1" loopers_w9
# W9 새 테이블 4개 (local 프로필은 ddl-auto: none — 운영처럼 DDL 파일로 만든다)
docker exec -i "$RUN-mysql" mysql -uapplication -papplication --default-character-set=utf8mb4 loopers_w9 < "$REPO/docs/week9/schema.sql"
echo "[w9] docs/week9/schema.sql 적용 (ranking_snapshot · ranking_snapshot_input · weekly_ranking · ranking_publication)"

# ---- 격리 Kafka 3대 (W7과 같은 설정) ----
VOTERS="1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093"
for id in 1 2 3; do
  port="${KAFKA_PORTS[$((id-1))]}"
  docker run -d --name "$RUN-kafka-$id" --network "$NET" --network-alias "kafka-$id" -p "${port}:19092" \
    -e CLUSTER_ID=bG9vcGVyc3c5AGthZrYaAA -e KAFKA_NODE_ID=$id -e KAFKA_PROCESS_ROLES=broker,controller \
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
for id in 1 2 3; do wait_until "Kafka $id 준비" 120 sh -c "docker logs $RUN-kafka-$id 2>&1 | grep -q 'Kafka Server started'"; done
# topic은 앱보다 먼저 만든다 — 없을 때 구독한 consumer는 metadata를 새로 받을 때까지 못 읽는다
for topic in order-events coupon-issue-requests; do
  docker exec "$RUN-kafka-1" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --create --topic "$topic" \
    --partitions 3 --replication-factor 3 --config min.insync.replicas=2 >/dev/null
done
echo "[w9] topic order-events · coupon-issue-requests (partition 3 · RF 3 · min.insync 2)"

# ---- pg-sim (1초 뒤 SUCCESS + callback) ----
python3 -I "$REPO/docs/week6/k6/pg-sim.py" > "$WORK/pg-sim.log" 2>&1 &
PIDS+=($!)
wait_until "pg-sim 준비" 15 curl -sf "$PG_URL/_stats"

# ---- streamer (구매 활동 consumer) ----
java -jar "$STREAMER_JAR" \
  --spring.profiles.active=local --server.port=0 --management.server.port=0 \
  --datasource.mysql-jpa.main.jdbc-url="$W9_JDBC_URL" --spring.kafka.bootstrap-servers="$W9_BOOTSTRAP" \
  --spring.jpa.hibernate.ddl-auto=update > "$W9_STREAMER_LOG" 2>&1 &
PIDS+=($!)
wait_until "streamer가 order-events partition을 받음" 120 sh -c "grep -q 'partitions assigned: \[.*order-events' '$W9_STREAMER_LOG'"

api_act() { # 막 이름 · 테스트 메서드
  echo "[w9] $1"
  (cd "$REPO" && ./gradlew :apps:commerce-api:isolatedRun --tests "*W9ConnectionRun.$2" -q) \
    || { cp -R "$REPO/apps/commerce-api/build/test-results/isolatedRun" "$OUT/api-test-results" 2>/dev/null; exit 1; }
}

batch_run() { # 이름 · 추가 인자...
  local name="$1"; shift
  set +e
  java -jar "$BATCH_JAR" --spring.profiles.active=local \
    --datasource.mysql-jpa.main.jdbc-url="$W9_JDBC_URL" \
    --job.name=weeklyRankingJob "periodStart=$PERIOD_START" "$@" > "$OUT/batch-$name.log" 2>&1
  local code=$?
  set -e
  echo "[w9] batch $name 명령: --job.name=weeklyRankingJob periodStart=$PERIOD_START $* → 종료 코드 $code"
  echo "batch $name: periodStart=$PERIOD_START $* → exit $code" >> "$OUT/summary.txt"
  return $code
}

# ---- 1막 ----
export W9_DDL_AUTO=update   # 1막만 api 테이블을 만든다
api_act "1막 — 주문 A·B·C 확정 → relay → streamer" act1_confirmOrdersAndRelay
unset W9_DDL_AUTO
PERIOD_START="$(python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['periodStart'])" "$W9_FACTS")"
echo "[w9] 주 = $PERIOD_START (주문 확정 시각의 UTC 월요일)"

# ---- batch ① 정기 명령 ----
batch_run "1-regular"

# ---- 2막 ----
api_act "2막 — 랭킹 API = r1 (7·9·8)" act2_rankingApiShowsR1

# ---- 3막 ----
api_act "3막 — 같은 이벤트 재발행 + 같은 주 늦은 구매" act3_republishSameEventAndLatePurchase

# ---- batch ② 같은 정기 명령 다시 → 이미 끝난 작업이라 거절되어야 한다 ----
if batch_run "2-regular-again"; then echo "[w9] 끝난 작업이 다시 돌았습니다 — 기대와 다름"; exit 1; fi
grep -q "JobInstanceAlreadyCompleteException" "$OUT/batch-2-regular-again.log"
echo "[w9] 같은 명령은 '이미 끝난 작업'으로 거절됨 (늦은 구매가 r1에 몰래 섞이지 않음)"

# ---- 4막 ----
api_act "4막 — 랭킹 API는 여전히 r1, r1 입력 그대로" act4_lateArrivalDoesNotChangeR1

# ---- batch ③ 재계산 ----
batch_run "3-recompute" --recompute=true

# ---- 5막 ----
api_act "5막 — 랭킹 API = r2 (8·7·9)" act5_rankingApiShowsR2

# ---- 실행 기록 요약 ----
docker exec "$RUN-mysql" mysql -uapplication -papplication -t loopers_w9 -e "
SELECT i.JOB_INSTANCE_ID, e.JOB_EXECUTION_ID, e.STATUS, e.EXIT_CODE,
       MAX(CASE WHEN p.PARAMETER_NAME = 'periodStart' THEN p.PARAMETER_VALUE END) AS periodStart,
       MAX(CASE WHEN p.PARAMETER_NAME = 'revision' THEN p.PARAMETER_VALUE END) AS revision
  FROM BATCH_JOB_INSTANCE i JOIN BATCH_JOB_EXECUTION e ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID
  JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
 GROUP BY i.JOB_INSTANCE_ID, e.JOB_EXECUTION_ID, e.STATUS, e.EXIT_CODE ORDER BY e.JOB_EXECUTION_ID;
SELECT snapshot_id, status, input_count, input_quantity_sum, LEFT(input_checksum, 12) AS checksum FROM ranking_snapshot ORDER BY snapshot_id;
SELECT period_start, snapshot_id, revision FROM ranking_publication;
SELECT snapshot_id, product_id, score, rank_no FROM weekly_ranking ORDER BY snapshot_id, rank_no;" 2>/dev/null | tee -a "$OUT/summary.txt"
grep -E "eventId=$(python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['eventIdA'])" "$W9_FACTS")" "$W9_STREAMER_LOG" \
  | grep -E "구매 활동 저장|no-op" > "$OUT/streamer-receipts-orderA.log" || true

echo "[w9] 확인 끝 — 결과 $OUT"
cleanup
