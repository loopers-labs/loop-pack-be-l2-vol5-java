#!/usr/bin/env bash
# EXP-007 · EXP-008: 확정은 commit 됐는데 이벤트가 broker 에 닿지 않는 누락을 두 앱 프로세스로 재현한다.
# 실행: 저장소 루트에서 `bash docs/week6/gap/run.sh`
# 필요: docker(compose 의 kafka), 로컬 redis(6379), kcat, jq, JDK 21. MySQL 은 이 실행만의 컨테이너를 13306 에 띄우고 지운다
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SUFFIX="$(date +%Y%m%d%H%M%S)"
TOPIC="order-events-gap-${SUFFIX}"
GROUP="purchase-activity-gap-${SUFFIX}"
DB="loopers_gap"
WORK="${ROOT}/build/gap-${SUFFIX}"
RESULT="${ROOT}/docs/week6/gap/result.md"
COMPOSE="docker compose -f ${ROOT}/docker/infra-compose.yml"
MYSQL="loopers-gap-mysql-${SUFFIX}"
BROKER="localhost:19092"
API="http://127.0.0.1:18080"
API_ADMIN="http://127.0.0.1:18081"
STREAMER_ADMIN="http://127.0.0.1:18082"
PG_CONTROL="http://127.0.0.1:8091"
ADMIN_AUTH="admin:local-only-not-a-secret"
USER_ID=1
PIDS=()

mkdir -p "${WORK}"

cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [ -n "${pid}" ] && kill "${pid}" 2>/dev/null || true
  done
  docker exec kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --delete --topic "${TOPIC}" >/dev/null 2>&1 || true
  docker rm -f "${MYSQL}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

step() { printf '\n== %s\n' "$1"; }
sql() { docker exec "${MYSQL}" mysql --default-character-set=utf8mb4 -uroot -proot "${DB}" -e "$1" 2>/dev/null; }
kafka() { docker exec kafka /opt/bitnami/kafka/bin/"$@"; }

wait_up() {
  local url="$1" name="$2"
  for _ in $(seq 1 120); do
    if curl -sf "${url}/actuator/health" >/dev/null 2>&1; then
      echo "${name} UP"
      return 0
    fi
    sleep 1
  done
  echo "${name} 가 120 초 안에 뜨지 않았다. 로그: ${WORK}/${name}.log" >&2
  exit 1
}

wait_for() {
  local description="$1" command="$2"
  for _ in $(seq 1 60); do
    if eval "${command}"; then
      return 0
    fi
    sleep 1
  done
  echo "${description} 이(가) 60 초 안에 되지 않았다" >&2
  exit 1
}

step "빌드"
(cd "${ROOT}" && ./gradlew --offline -q :apps:commerce-api:bootJar :apps:commerce-streamer:bootJar \
  :apps:commerce-api:testClasses)
API_JAR="$(ls -t "${ROOT}"/apps/commerce-api/build/libs/commerce-api-*.jar | head -1)"
STREAMER_JAR="$(ls -t "${ROOT}"/apps/commerce-streamer/build/libs/commerce-streamer-*.jar | head -1)"
(cd "${WORK}" && unzip -qo "${API_JAR}" 'BOOT-INF/lib/jackson-*')

step "인프라: kafka, 실행 전용 MySQL ${MYSQL}(13306), topic ${TOPIC}"
${COMPOSE} up -d kafka >/dev/null 2>&1
docker run -d --name "${MYSQL}" -p 13306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE="${DB}" \
  mysql:8.0 --transaction-isolation=READ-COMMITTED --character-set-server=utf8mb4 >/dev/null
wait_for "mysql 기동" "docker exec ${MYSQL} mysql -uroot -proot -e 'select 1' ${DB} >/dev/null 2>&1"
kafka kafka-topics.sh --bootstrap-server localhost:9092 --create --topic "${TOPIC}" \
  --partitions 3 --replication-factor 1 >/dev/null

step "PG fixture · commerce-api · commerce-streamer"
java -cp "${ROOT}/apps/commerce-api/build/classes/java/test:${ROOT}/apps/commerce-api/build/resources/test:${WORK}/BOOT-INF/lib/*" \
  com.loopers.support.pg.PgFixtureServer 8090 8091 >"${WORK}/pg.log" 2>&1 &
PIDS+=($!)

COMMON=(
  --spring.profiles.active=local
  --datasource.mysql-jpa.main.jdbc-url="jdbc:mysql://localhost:13306/${DB}"
  --datasource.mysql-jpa.main.username=root
  --datasource.mysql-jpa.main.password=root
  "--datasource.redis.replicas[0].host=localhost"
  "--datasource.redis.replicas[0].port=6379"
  --kafka.topics.order-events="${TOPIC}"
  --spring.jpa.show-sql=false
)
java -jar "${API_JAR}" "${COMMON[@]}" \
  --server.port=18080 --management.server.port=18081 \
  --pg.callback-url="${API}/api/v1/payments/callback" \
  --pg.ssl-bundle=pg \
  --spring.ssl.bundle.jks.pg.truststore.location="file:${ROOT}/apps/commerce-api/src/test/resources/pg-fixture-truststore.p12" \
  --spring.ssl.bundle.jks.pg.truststore.password=local-only-not-a-secret \
  --spring.ssl.bundle.jks.pg.truststore.type=PKCS12 \
  --payment.sweeper.enabled=false --payment.unresolved-metrics.enabled=false \
  >"${WORK}/api.log" 2>&1 &
PIDS+=($!)
wait_up "${API_ADMIN}" api
java -jar "${STREAMER_JAR}" "${COMMON[@]}" \
  --server.port=18085 --management.server.port=18082 \
  --kafka.groups.purchase-activity="${GROUP}" \
  >"${WORK}/streamer.log" 2>&1 &
PIDS+=($!)
wait_up "${STREAMER_ADMIN}" streamer

step "상품 준비"
BRAND_ID=$(curl -sS --fail-with-body -u "${ADMIN_AUTH}" -X POST "${API}/api-admin/v1/brands" -H 'Content-Type: application/json' \
  -d "{\"name\":\"gap-${SUFFIX}\",\"description\":\"W6 누락 재현\"}" | jq -r '.data.id')
product() {
  local id
  id=$(curl -sS --fail-with-body -u "${ADMIN_AUTH}" -X POST "${API}/api-admin/v1/products" -H 'Content-Type: application/json' \
    -d "{\"brandId\":${BRAND_ID},\"name\":\"$1\",\"price\":$2}" | jq -r '.data.id')
  curl -sS --fail-with-body -u "${ADMIN_AUTH}" -X PUT "${API}/api-admin/v1/products/${id}/stock" -H 'Content-Type: application/json' \
    -d '{"quantity":100}' >/dev/null
  echo "${id}"
}
COAT=$(product "코트" 3000)
SHIRT=$(product "셔츠" 1000)

confirm_order() {
  local order_id provider status
  order_id=$(curl -sS --fail-with-body -X POST "${API}/api/v1/orders" -H "X-USER-ID: ${USER_ID}" -H 'Content-Type: application/json' \
    -d "{\"lines\":[{\"productId\":${COAT},\"quantity\":2},{\"productId\":${SHIRT},\"quantity\":1}]}" | jq -r '.data.id')
  curl -s -X POST "${API}/api/v1/orders/${order_id}/confirm" -H "X-USER-ID: ${USER_ID}" \
    -H 'Content-Type: application/json' \
    -d "{\"paymentAttemptKey\":\"gap-${SUFFIX}-${order_id}\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}" \
    >"${WORK}/confirm-${order_id}.json"
  provider=$(printf 'LP-ORD-%010d' "${order_id}")
  curl -sS --fail-with-body -X POST "${PG_CONTROL}/approve?orderId=${provider}" >"${WORK}/approve-${order_id}.txt"
  wait_for "주문 ${order_id} 확정" \
    "[ \"\$(curl -sf ${API}/api/v1/orders/${order_id} -H 'X-USER-ID: ${USER_ID}' | jq -r '.data.status')\" = CONFIRMED ]"
  echo "${order_id}"
}

publish_failures() {
  curl -sf "${API_ADMIN}/actuator/prometheus" \
    | awk '/^order_event_publish_total/ && /result="failure"/ {s+=$2} END {print s+0}'
}

step "주문 A: topic 의 max.message.bytes=1 → broker 가 거절"
kafka kafka-configs.sh --bootstrap-server localhost:9092 --alter --entity-type topics --entity-name "${TOPIC}" \
  --add-config max.message.bytes=1 >/dev/null
sleep 2
FAILURES_BEFORE=$(publish_failures)
ORDER_A=$(confirm_order)
wait_for "주문 A 의 발행 실패 지표" "[ \$(publish_failures) -gt ${FAILURES_BEFORE} ]"

step "주문 B: max.message.bytes 를 되돌림 → broker · streamer 까지"
kafka kafka-configs.sh --bootstrap-server localhost:9092 --alter --entity-type topics --entity-name "${TOPIC}" \
  --delete-config max.message.bytes >/dev/null
sleep 2
ORDER_B=$(confirm_order)
wait_for "주문 B 의 구매 활동" \
  "[ \"\$(sql \"select count(*) from purchase_activity where order_id = ${ORDER_B}\" | tail -1)\" = 2 ]"
sleep 3

step "관찰"
KCAT=$(kcat -b "${BROKER}" -C -t "${TOPIC}" -o beginning -e -q -f 'partition=%p offset=%o key=%k headers=%h\n%s\n')
GROUP_OFFSETS=$(kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group "${GROUP}" 2>/dev/null \
  | awk 'NR>1 && NF {print $2, $3, $4, $5, $6}')
ORDERS=$(sql "select id, status, total_amount, paid_amount, date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') confirmed_at from orders where id in (${ORDER_A}, ${ORDER_B}) order by id")
PAYMENTS=$(sql "select order_id, status, provider_order_id, transaction_key from payment where order_id in (${ORDER_A}, ${ORDER_B}) order by order_id")
STOCKS=$(sql "select id, name, quantity from product where id in (${COAT}, ${SHIRT}) order by id")
RECEIPTS=$(docker exec "${MYSQL}" mysql --default-character-set=utf8mb4 -uroot -proot "${DB}" -e \
  "select event_id, order_id, date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') confirmed_at, items_digest from order_confirmation_receipt order by order_id" 2>/dev/null)
ACTIVITIES=$(sql "select order_id, product_id, quantity from purchase_activity order by order_id, product_id")
HOLDS=$(sql "select count(*) holds from order_event_hold")
API_METRICS=$(curl -sf "${API_ADMIN}/actuator/prometheus" | grep '^order_event_publish_total' || true)
STREAMER_METRICS=$(curl -sf "${STREAMER_ADMIN}/actuator/prometheus" | grep '^purchase_activity_total' || true)
API_FAILURE_LOG=$(grep -h "주문 이벤트를 보내지 못했다" "${WORK}/api.log" | head -1 | cut -c1-400 || true)
COMMIT=$(cd "${ROOT}" && git rev-parse --short HEAD 2>/dev/null || echo unknown)

cat >"${RESULT}" <<MD
# W6 누락 재현 결과 (EXP-007 · EXP-008)

- 실행: \`bash docs/week6/gap/run.sh\` · commit \`${COMMIT}\` · $(date -u +%Y-%m-%dT%H:%M:%SZ)
- topic \`${TOPIC}\`(partition 3) · group \`${GROUP}\` · MySQL 컨테이너 \`${MYSQL}\` — 실행마다 새로 만들고 실행 끝에 지운다
- 두 앱과 PG fixture 를 프로세스로 띄웠다. 주문 A 는 topic 의 \`max.message.bytes=1\` 로 broker 가 거절하게 하고, 되돌린 뒤 주문 B 를 확정했다
- 주문은 코트(3,000) × 2 · 셔츠(1,000) × 1 이다. 확정 시각은 고정하지 않은 실제 시각이다 — 결제 API 가 \`Instant.now()\` 를 쓴다
- broker 는 우리 코드가 아니라 \`kcat\` 으로 읽었다. committed offset 은 \`kafka-consumer-groups.sh\` 로 읽었다(kcat 은 group offset 을 보여 주지 않는다)

## 주문 A (${ORDER_A}) · 주문 B (${ORDER_B})

\`\`\`
${ORDERS}
\`\`\`

결제 · PG 거래

\`\`\`
${PAYMENTS}
\`\`\`

재고(100 에서 시작, 두 주문이 각각 코트 2 · 셔츠 1 을 소비)

\`\`\`
${STOCKS}
\`\`\`

## broker — kcat

\`\`\`
${KCAT:-(record 없음)}
\`\`\`

## committed offset — kafka-consumer-groups.sh (TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG)

\`\`\`
${GROUP_OFFSETS}
\`\`\`

## streamer — 처리 기록 · 구매 활동 · 보류

\`\`\`
${RECEIPTS}
\`\`\`

\`\`\`
${ACTIVITIES}
\`\`\`

\`\`\`
${HOLDS}
\`\`\`

## 지표

\`\`\`
${API_METRICS}
${STREAMER_METRICS}
\`\`\`

주문 A 의 발행 실패 로그(api)

\`\`\`
${API_FAILURE_LOG}
\`\`\`
MD

step "끝. 결과: ${RESULT}, 로그: ${WORK}"
