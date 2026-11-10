#!/usr/bin/env bash
# 7주차 눈으로 보기 — 실제 서비스처럼 띄운다: Kafka 3대 + kafka-ui + pg-sim + commerce-api(relay 포함) + commerce-streamer.
# DB는 로컬 MySQL(docker-mysql-1)에 loopers_w7을 새로 만든다. docker/infra-compose.yml의 kafka-1~3·kafka-ui와 그 볼륨은 건드리지 않는다.
# 실행: 저장소 루트에서  bash docs/week7/live/up.sh     정리: bash docs/week7/live/down.sh
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"
LIVE="$REPO/docs/week7/live"
RUN="${TMPDIR:-/tmp}/w7-live"; mkdir -p "$RUN"
NET=w7-live-net
VOTERS="1@w7-kafka-1:9093,2@w7-kafka-2:9093,3@w7-kafka-3:9093"

wait_until() { local what="$1" max="$2"; shift 2
  for _ in $(seq 1 "$max"); do if "$@" >/dev/null 2>&1; then echo "[live] $what"; return 0; fi; sleep 1; done
  echo "[live] 시간 초과: $what (로그: $RUN)"; return 1; }

for p in 8080 8081 8082 8085 8086 9098 29092 29093 29094; do
  lsof -iTCP:$p -sTCP:LISTEN -n -P >/dev/null 2>&1 && { echo "[live] 포트 $p가 이미 쓰이고 있습니다"; exit 1; }
done
docker ps --format '{{.Names}}' | grep -q '^docker-mysql-1$' || { echo "[live] 로컬 MySQL(docker-mysql-1)을 먼저 띄우세요"; exit 1; }

# ---- DB: loopers_w7 (W5 스키마 + W6 consumer 테이블 + W7 Outbox) ----
# W5·W6 schema 파일은 utf8mb4_general_ci를 적어 두었고 W7 schema는 DB 기본값을 따른다 → DB를 general_ci로 만들어 JOIN이 되게 맞춘다
docker exec -i docker-mysql-1 mysql -uroot -proot -e "DROP DATABASE IF EXISTS loopers_w7; CREATE DATABASE loopers_w7 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; GRANT ALL PRIVILEGES ON loopers_w7.* TO 'application'@'%';" 2>/dev/null
cat "$REPO/docs/week5/k6/schema.sql" "$REPO/docs/week6/schema.sql" "$REPO/docs/week7/schema.sql" "$REPO/docs/week6/k6/schema-extra.sql" "$REPO/docs/week5/k6/seed.sql" \
  | docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_w7 2>/dev/null >/dev/null
echo "[live] DB loopers_w7 (사용자 1,000 · 상품 10 · 재고 각 1,000,000)"

# ---- Kafka 3대 + kafka-ui ----
docker network create "$NET" >/dev/null 2>&1 || true
for id in 1 2 3; do
  docker run -d --name "w7-kafka-$id" --network "$NET" -p "2909$((id+1)):19092" \
    -e CLUSTER_ID=bG9vcGVyc3c3bGl2ZQAAAA -e KAFKA_NODE_ID=$id -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS="$VOTERS" \
    -e KAFKA_LISTENERS="PLAINTEXT://:9092,CONTROLLER://:9093,PLAINTEXT_HOST://:19092" \
    -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://w7-kafka-$id:9092,PLAINTEXT_HOST://localhost:2909$((id+1))" \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP="PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT" \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false -e KAFKA_DEFAULT_REPLICATION_FACTOR=3 -e KAFKA_MIN_INSYNC_REPLICAS=2 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=3 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=3 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=2 -e KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE=false \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 apache/kafka:3.8.1 >/dev/null
done
for id in 1 2 3; do wait_until "Kafka $id 준비" 120 sh -c "docker logs w7-kafka-$id 2>&1 | grep -q 'Kafka Server started'"; done
docker exec w7-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server w7-kafka-1:9092 --create --if-not-exists --topic order-events \
  --partitions 3 --replication-factor 3 --config min.insync.replicas=2 >/dev/null
docker exec w7-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server w7-kafka-1:9092 --create --if-not-exists --topic order-events.DLT \
  --partitions 3 --replication-factor 3 --config min.insync.replicas=2 >/dev/null
docker run -d --name w7-kafka-ui --network "$NET" -p 9098:8080 \
  -e KAFKA_CLUSTERS_0_NAME=w7-live -e KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=w7-kafka-1:9092,w7-kafka-2:9092,w7-kafka-3:9092 \
  provectuslabs/kafka-ui:latest >/dev/null
echo "[live] topic order-events · order-events.DLT (partition 3 · RF 3 · min.insync 2)"

# ---- pg-sim · 앱 ----
python3 -I "$REPO/docs/week6/k6/pg-sim.py" > "$RUN/pg-sim.log" 2>&1 & echo $! > "$RUN/pg-sim.pid"
(cd "$REPO" && ./gradlew :apps:commerce-api:bootJar :apps:commerce-streamer:bootJar -q)
API_JAR="$(ls "$REPO"/apps/commerce-api/build/libs/*.jar | grep -v plain | head -1)"
STREAMER_JAR="$(ls "$REPO"/apps/commerce-streamer/build/libs/*.jar | grep -v plain | head -1)"
cp "$API_JAR" "$RUN/commerce-api.jar"; cp "$STREAMER_JAR" "$RUN/commerce-streamer.jar"
ARGS="--spring.profiles.active=local --spring.config.additional-location=file:$LIVE/config-live.yml"
java -jar "$RUN/commerce-api.jar" $ARGS > "$RUN/api.log" 2>&1 & echo $! > "$RUN/api.pid"
java -jar "$RUN/commerce-streamer.jar" $ARGS > "$RUN/streamer.log" 2>&1 & echo $! > "$RUN/streamer.pid"
wait_until "pg-sim 준비 (8082)" 15 curl -sf localhost:8082/_stats
wait_until "commerce-api 준비 (8080 · 관리 8081)" 120 curl -sf localhost:8081/actuator/health
wait_until "commerce-streamer 준비 (8085 · 관리 8086)" 120 sh -c "grep -q 'partitions assigned: \[.*order-events' '$RUN/streamer.log'"
wait_until "kafka-ui 준비" 90 curl -sf localhost:9098/

cat <<MSG

[live] 준비 끝
  kafka-ui   http://localhost:9098   (Topics → order-events → Messages, Consumers → commerce-streamer…)
  로그       tail -f $RUN/api.log $RUN/streamer.log
  주문 1건   bash docs/week7/live/order.sh            (상품·수량은 README)
  상태 보기  bash docs/week7/live/status.sh
MSG
