#!/usr/bin/env bash
# C-7·C-8 — 새 격리 실행에서 W6 누락 상태를 재현하고(1막, W6 revision) 같은 실행 안에서 W7으로 복구한다(2막).
#
# 이 스크립트가 격리 자원의 수명주기를 소유한다: MySQL 1 · Kafka 3대 · pg-sim · W6 streamer · W6 revision worktree.
# 1막과 2막 사이에는 DB·broker·PG를 초기화하지 않는다. 모든 확인이 끝난 뒤에만 자신이 만든 자원을 지운다.
# 실패하면 조사할 수 있게 자원을 남기고, 지우는 명령을 출력한다.
#
# 실행: 저장소 루트(W7 코드)에서  bash docs/week7/c7/run.sh
# 결과: docs/week7/c7/results/<실행 ID>/{facts.json, result.json}
set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
W6_REVISION="${W6_REVISION:-388bc941}"          # W6 머지 커밋 (after-commit 직접 발행 경로가 있는 마지막 revision)
RUN="c7-$(date +%Y%m%d-%H%M%S)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/${RUN}.XXXX")"
OUT="$REPO/docs/week7/c7/results/$RUN"
MYSQL_PORT="${MYSQL_PORT:-33307}"
KAFKA_PORTS=(39092 39093 39094)
NET="$RUN-net"
PG_URL="http://127.0.0.1:8082"
mkdir -p "$OUT"

export C7_JDBC_URL="jdbc:mysql://localhost:${MYSQL_PORT}/loopers_c7"
export C7_BOOTSTRAP="localhost:${KAFKA_PORTS[0]},localhost:${KAFKA_PORTS[1]},localhost:${KAFKA_PORTS[2]}"
export C7_PG_URL="$PG_URL"
export C7_FACTS="$OUT/facts.json"
export C7_RESULT="$OUT/result.json"
export C7_W6_REVISION="$(git -C "$REPO" rev-parse "$W6_REVISION")"

PIDS=()
cleanup() {
  echo "[c7] 정리: $RUN"
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
  docker rm -f "$RUN-mysql" "$RUN-kafka-1" "$RUN-kafka-2" "$RUN-kafka-3" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  git -C "$REPO" worktree remove --force "$WORK/w6" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
on_exit() {
  local code=$?
  if [ $code -ne 0 ]; then
    echo "[c7] 실패(exit $code) — 조사용으로 자원을 남깁니다. 로그: $WORK"
    echo "[c7] 지우려면: kill ${PIDS[*]:-} ; docker rm -f $RUN-mysql $RUN-kafka-1 $RUN-kafka-2 $RUN-kafka-3 ; docker network rm $NET ; git worktree remove --force $WORK/w6"
  fi
}
trap on_exit EXIT

wait_until() { # 설명 · 최대 초 · 명령
  local what="$1" max="$2"; shift 2
  for _ in $(seq 1 "$max"); do if "$@" >/dev/null 2>&1; then echo "[c7] $what"; return 0; fi; sleep 1; done
  echo "[c7] 시간 초과: $what"; return 1
}

echo "[c7] 실행 $RUN · W6 revision $C7_W6_REVISION · 작업 폴더 $WORK"
lsof -iTCP:8082 -sTCP:LISTEN -n -P >/dev/null 2>&1 && { echo "[c7] 8082(pg-sim)가 이미 쓰이고 있습니다"; exit 1; }

# ---- 격리 MySQL ----
docker network create "$NET" >/dev/null
docker run -d --name "$RUN-mysql" --network "$NET" -p "${MYSQL_PORT}:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=loopers_c7 -e MYSQL_USER=application -e MYSQL_PASSWORD=application \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci >/dev/null
wait_until "MySQL 준비" 90 docker exec "$RUN-mysql" mysql -uapplication -papplication -e "SELECT 1" loopers_c7

# ---- 격리 Kafka 3대 (modules/kafka testFixtures KafkaCluster와 같은 설정) ----
VOTERS="1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093"
for id in 1 2 3; do
  port="${KAFKA_PORTS[$((id-1))]}"
  docker run -d --name "$RUN-kafka-$id" --network "$NET" --network-alias "kafka-$id" -p "${port}:19092" \
    -e CLUSTER_ID=bG9vcGVyc3c3AGthZrYaAA -e KAFKA_NODE_ID=$id -e KAFKA_PROCESS_ROLES=broker,controller \
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
# topic은 앱보다 먼저 만든다 — 없을 때 구독한 consumer는 metadata를 새로 받을 때(최대 5분)까지 못 읽는다
docker exec "$RUN-kafka-1" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --create --topic order-events \
  --partitions 3 --replication-factor 3 --config min.insync.replicas=2 >/dev/null
echo "[c7] topic order-events (partition 3 · RF 3 · min.insync 2)"

# ---- pg-sim (auto: 1초 뒤 SUCCESS + callback). 1막·2막 내내 같은 프로세스 — 호출 수를 이어서 센다 ----
python3 -I "$REPO/docs/week6/k6/pg-sim.py" > "$WORK/pg-sim.log" 2>&1 &
PIDS+=($!)
wait_until "pg-sim 준비" 15 curl -sf "$PG_URL/_stats"

# ---- W6 revision worktree: 1막 테스트를 넣고, streamer(기존 consumer)를 띄운다 ----
git -C "$REPO" worktree add --detach "$WORK/w6" "$W6_REVISION" >/dev/null
mkdir -p "$WORK/w6/apps/commerce-api/src/test/java/com/loopers/c7"
cp "$REPO/docs/week7/c7/W6GapPreparationRun.java" "$WORK/w6/apps/commerce-api/src/test/java/com/loopers/c7/"
(cd "$WORK/w6" && ./gradlew :apps:commerce-streamer:bootJar -q)
java -jar "$(ls "$WORK"/w6/apps/commerce-streamer/build/libs/*.jar | head -1)" \
  --spring.profiles.active=local --server.port=0 --management.server.port=0 \
  --datasource.mysql-jpa.main.jdbc-url="$C7_JDBC_URL" --spring.kafka.bootstrap-servers="$C7_BOOTSTRAP" \
  --spring.jpa.hibernate.ddl-auto=update > "$WORK/streamer.log" 2>&1 &
PIDS+=($!)
wait_until "streamer가 order-events partition을 받음" 120 sh -c "grep -q 'partitions assigned: \[.*order-events' '$WORK/streamer.log'"

# ---- 1막: W6 코드로 누락 상태 만들기 ----
echo "[c7] 1막 — W6 revision으로 두 주문 확정 (A: send 직전 중단 · B: 발행·소비)"
(cd "$WORK/w6" && ./gradlew :apps:commerce-api:test --tests 'com.loopers.c7.W6GapPreparationRun' -q) \
  || { cp -R "$WORK/w6/apps/commerce-api/build/test-results" "$WORK/w6-test-results" 2>/dev/null; exit 1; }
test -s "$C7_FACTS"

# ---- 같은 DB에 W7 Outbox 구조 연결 (event_outbox는 지우지 않는다 — A의 eventId가 남은 곳) ----
grep -v 'DROP TABLE' "$REPO/docs/week7/schema.sql" | docker exec -i "$RUN-mysql" mysql -uapplication -papplication --default-character-set=utf8mb4 loopers_c7
echo "[c7] W7 테이블 추가 (order_outbox · shedlock)"

# ---- 2막: W7 코드로 같은 사건 복구 ----
echo "[c7] 2막 — W7 복구 도구 + relay, 기존 streamer가 소비"
(cd "$REPO" && ./gradlew :apps:commerce-api:c7Recovery -q)
test -s "$C7_RESULT"

echo "[c7] 확인 끝 — 결과 $OUT"
cleanup
