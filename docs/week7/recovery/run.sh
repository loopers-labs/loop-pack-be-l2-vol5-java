#!/usr/bin/env bash
# EXP-014: W6 실행물로 발행 누락 상태를 만들고, 같은 DB 를 W7 로 바꿔 복구한다.
# 주문 B: W6 에서 발행과 소비까지 끝난 주문. 주문 A: 확정 commit 뒤 record 가 producer 버퍼에 있는 동안 W6 api 를 kill -9 한 주문.
# 실행: 저장소 루트에서 `bash docs/week7/recovery/run.sh`
# 필요: docker(compose 의 kafka), 로컬 redis(6379), kcat, jq, JDK 21, git(upstream/seeun0210). MySQL 은 이 실행만의 컨테이너를 13306 에 띄우고 지운다
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SUFFIX="$(date +%Y%m%d%H%M%S)"
W6_REV="upstream/seeun0210"
TOPIC="order-events-recovery-${SUFFIX}"
GROUP="purchase-activity-recovery-${SUFFIX}"
DB="loopers_recovery"
WORK="${ROOT}/build/recovery-${SUFFIX}"
W6_DIR="${WORK}/w6"
RESULT="${ROOT}/docs/week7/recovery/result.md"
COMPOSE="docker compose -f ${ROOT}/docker/infra-compose.yml"
MYSQL="loopers-recovery-mysql-${SUFFIX}"
BROKER="localhost:19092"
API="http://127.0.0.1:18080"
API_ADMIN="http://127.0.0.1:18081"
STREAMER_ADMIN="http://127.0.0.1:18082"
PG_CONTROL="http://127.0.0.1:8091"
ADMIN_AUTH="admin:local-only-not-a-secret"
USER_ID=1
PIDS=()
KAFKA_WAS_RUNNING="$(docker ps -q --filter name='^kafka$')"

mkdir -p "${WORK}"

cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [ -n "${pid}" ] && kill "${pid}" 2>/dev/null || true
  done
  docker exec kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --delete --topic "${TOPIC}" >/dev/null 2>&1 || true
  docker rm -f "${MYSQL}" >/dev/null 2>&1 || true
  (cd "${ROOT}" && git worktree remove --force "${W6_DIR}" >/dev/null 2>&1) || true
  [ -n "${KAFKA_WAS_RUNNING}" ] || ${COMPOSE} stop kafka >/dev/null 2>&1 || true
}
trap cleanup EXIT

step() { printf '\n== %s\n' "$1"; }
sql() { docker exec "${MYSQL}" mysql --default-character-set=utf8mb4 -uroot -proot "${DB}" -e "$1" 2>/dev/null; }
scalar() { sql "$1" | tail -1; }
kafka() { docker exec kafka /opt/bitnami/kafka/bin/"$@"; }
pg_stats() { curl -sf "${PG_CONTROL}/stats"; }
records_for() { kcat -b "${BROKER}" -C -t "${TOPIC}" -o beginning -e -q -f '%k\n' 2>/dev/null | grep -cx "$1" || true; }

wait_up() {
  local url="$1" name="$2"
  for _ in $(seq 1 120); do
    if curl -sf "${url}/actuator/health" >/dev/null 2>&1; then
      echo "${name} UP"
      return 0
    fi
    sleep 1
  done
  echo "${name} 가 120 초 안에 뜨지 않았다. 로그: ${WORK}" >&2
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

step "빌드: W6(${W6_REV}) 와 W7(HEAD)"
(cd "${ROOT}" && git worktree add --detach "${W6_DIR}" "${W6_REV}" >/dev/null)
(cd "${W6_DIR}" && ./gradlew --offline -q :apps:commerce-api:bootJar :apps:commerce-streamer:bootJar)
(cd "${ROOT}" && ./gradlew --offline -q :apps:commerce-api:bootJar :apps:commerce-api:testClasses)
W6_API_JAR="$(ls -t "${W6_DIR}"/apps/commerce-api/build/libs/commerce-api-*.jar | head -1)"
W6_STREAMER_JAR="$(ls -t "${W6_DIR}"/apps/commerce-streamer/build/libs/commerce-streamer-*.jar | head -1)"
W7_API_JAR="$(ls -t "${ROOT}"/apps/commerce-api/build/libs/commerce-api-*.jar | head -1)"
W6_COMMIT="$(cd "${W6_DIR}" && git rev-parse --short HEAD)"
W7_COMMIT="$(cd "${ROOT}" && git rev-parse --short HEAD)"
(cd "${WORK}" && unzip -qo "${W7_API_JAR}" 'BOOT-INF/lib/jackson-*')

step "인프라: kafka, 실행 전용 MySQL ${MYSQL}(13306), topic ${TOPIC}"
${COMPOSE} up -d kafka >/dev/null 2>&1
wait_for "kafka 기동" "docker exec kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1"
docker run -d --name "${MYSQL}" -p 13306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE="${DB}" \
  mysql:8.0 --transaction-isolation=READ-COMMITTED --character-set-server=utf8mb4 >/dev/null
wait_for "mysql 기동" "docker exec ${MYSQL} mysql -uroot -proot -e 'select 1' ${DB} >/dev/null 2>&1"
kafka kafka-topics.sh --bootstrap-server localhost:9092 --create --topic "${TOPIC}" \
  --partitions 3 --replication-factor 1 >/dev/null

step "PG fixture"
java -cp "${ROOT}/apps/commerce-api/build/classes/java/test:${ROOT}/apps/commerce-api/build/resources/test:${WORK}/BOOT-INF/lib/*" \
  com.loopers.support.pg.PgFixtureServer 8090 8091 >"${WORK}/pg.log" 2>&1 &
PIDS+=($!)
wait_for "PG fixture 기동" "curl -sf ${PG_CONTROL}/stats >/dev/null 2>&1"

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

start_api() {
  local jar="$1" log="$2"
  shift 2
  java -jar "${jar}" "${COMMON[@]}" \
    --server.port=18080 --management.server.port=18081 \
    --pg.callback-url="${API}/api/v1/payments/callback" \
    --pg.ssl-bundle=pg \
    --spring.ssl.bundle.jks.pg.truststore.location="file:${ROOT}/apps/commerce-api/src/test/resources/pg-fixture-truststore.p12" \
    --spring.ssl.bundle.jks.pg.truststore.password=local-only-not-a-secret \
    --spring.ssl.bundle.jks.pg.truststore.type=PKCS12 \
    --payment.sweeper.enabled=false --payment.unresolved-metrics.enabled=false \
    "$@" >"${WORK}/${log}" 2>&1 &
  API_PID=$!
  PIDS+=("${API_PID}")
  wait_up "${API_ADMIN}" "api(${log})"
}

step "W6 api, W6 streamer"
start_api "${W6_API_JAR}" w6-api.log
java -jar "${W6_STREAMER_JAR}" "${COMMON[@]}" \
  --server.port=18085 --management.server.port=18082 \
  --kafka.groups.purchase-activity="${GROUP}" \
  >"${WORK}/w6-streamer.log" 2>&1 &
PIDS+=($!)
wait_up "${STREAMER_ADMIN}" streamer

step "상품 준비"
BRAND_ID=$(curl -sS --fail-with-body -u "${ADMIN_AUTH}" -X POST "${API}/api-admin/v1/brands" -H 'Content-Type: application/json' \
  -d "{\"name\":\"recovery-${SUFFIX}\",\"description\":\"W7 복구 재현\"}" | jq -r '.data.id')

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
  local order_id provider
  order_id=$(curl -sS --fail-with-body -X POST "${API}/api/v1/orders" -H "X-USER-ID: ${USER_ID}" -H 'Content-Type: application/json' \
    -d "{\"lines\":[{\"productId\":${COAT},\"quantity\":2},{\"productId\":${SHIRT},\"quantity\":1}]}" | jq -r '.data.id')
  curl -s -X POST "${API}/api/v1/orders/${order_id}/confirm" -H "X-USER-ID: ${USER_ID}" \
    -H 'Content-Type: application/json' \
    -d "{\"paymentAttemptKey\":\"recovery-${SUFFIX}-${order_id}\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}" \
    >"${WORK}/confirm-${order_id}.json"
  provider=$(printf 'LP-ORD-%010d' "${order_id}")
  curl -sS --fail-with-body -X POST "${PG_CONTROL}/approve?orderId=${provider}" >"${WORK}/approve-${order_id}.txt"
  wait_for "주문 ${order_id} 확정" \
    "[ \"\$(curl -sf ${API}/api/v1/orders/${order_id} -H 'X-USER-ID: ${USER_ID}' | jq -r '.data.status')\" = CONFIRMED ]"
  echo "${order_id}"
}

step "주문 B: W6 에서 발행과 소비까지"
ORDER_B=$(confirm_order)
wait_for "주문 B 의 구매 활동" "[ \"\$(scalar \"select count(*) from purchase_activity where order_id = ${ORDER_B}\")\" = 2 ]"

step "주문 A: W6 api 를 linger.ms=60000 으로 다시 띄워 확정한 뒤, record 가 버퍼에 있는 동안 kill -9"
kill "${API_PID}"
wait "${API_PID}" 2>/dev/null || true
start_api "${W6_API_JAR}" w6-api-a.log --spring.kafka.producer.properties.linger.ms=60000 --spring.jpa.hibernate.ddl-auto=none
ORDER_A=$(confirm_order)
kill -9 "${API_PID}"
KILLED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
sleep 5

step "준비 결과 기록"
EVENT_A="order-confirmed:v1:${ORDER_A}"
EVENT_B="order-confirmed:v1:${ORDER_B}"
PREPARED_ORDERS=$(sql "select id, status, date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') confirmed_at from orders where id in (${ORDER_A}, ${ORDER_B}) order by id")
PREPARED_ITEMS=$(sql "select order_id, product_id, quantity from order_item where order_id in (${ORDER_A}, ${ORDER_B}) order by order_id, product_id")
PREPARED_PAYMENTS=$(sql "select order_id, status, provider_order_id, transaction_key from payment where order_id in (${ORDER_A}, ${ORDER_B}) order by order_id")
PREPARED_STOCKS=$(sql "select id, name, quantity from product where id in (${COAT}, ${SHIRT}) order by id")
PREPARED_ACTIVITIES=$(sql "select id, order_id, product_id, quantity from purchase_activity order by order_id, product_id")
PREPARED_TABLES=$(sql "select table_name from information_schema.tables where table_schema = '${DB}' order by table_name")
PREPARED_RECORDS_A=$(records_for "${ORDER_A}")
PREPARED_RECORDS_B=$(records_for "${ORDER_B}")
PG_PREPARED=$(pg_stats)

step "W7 api 를 같은 DB 에 ddl-auto=update 로 띄우고 relay 를 켬"
start_api "${W7_API_JAR}" w7-api.log --spring.jpa.hibernate.ddl-auto=update \
  --outbox.relay.enabled=true --outbox.relay.interval=1s
SWITCHED_TABLES=$(sql "select table_name from information_schema.tables where table_schema = '${DB}' order by table_name")
PG_BEFORE_RECOVERY=$(pg_stats)
STOCKS_BEFORE_RECOVERY=$(sql "select id, quantity from product where id in (${COAT}, ${SHIRT}) order by id")
ACTIVITY_B_BEFORE=$(sql "select id, product_id, quantity, updated_at from purchase_activity where order_id = ${ORDER_B} order by id")

recover() {
  curl -sS --fail-with-body -u "${ADMIN_AUTH}" -X POST "${API}/api-admin/v1/orders/$1/confirmed-event/recovery" | jq -c '.data'
}

step "복구 1 회차"
RECOVERY1_A=$(recover "${ORDER_A}")
RECOVERY1_B=$(recover "${ORDER_B}")
wait_for "outbox 두 행 PUBLISHED" "[ \"\$(scalar \"select count(*) from order_event_outbox where status = 'PUBLISHED'\")\" = 2 ]"
wait_for "주문 A 의 구매 활동" "[ \"\$(scalar \"select count(*) from purchase_activity where order_id = ${ORDER_A}\")\" = 2 ]"
wait_for "주문 B 재발행 소비" "[ \"\$(records_for ${ORDER_B})\" = 2 ]"
sleep 3

step "복구 2 회차"
RECOVERY2_A=$(recover "${ORDER_A}")
RECOVERY2_B=$(recover "${ORDER_B}")
sleep 5

step "관찰"
PG_AFTER=$(pg_stats)
STOCKS_AFTER=$(sql "select id, quantity from product where id in (${COAT}, ${SHIRT}) order by id")
ACTIVITY_B_AFTER=$(sql "select id, product_id, quantity, updated_at from purchase_activity where order_id = ${ORDER_B} order by id")
ACTIVITIES=$(sql "select order_id, product_id, quantity from purchase_activity order by order_id, product_id")
RECEIPTS=$(sql "select event_id, order_id, date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') confirmed_at, items_digest from order_confirmation_receipt order by order_id")
HOLDS=$(scalar "select count(*) from order_event_hold")
OUTBOX=$(sql "select event_id, order_id, status, attempt_count, convert(payload using utf8mb4) payload from order_event_outbox order by order_id")
ORDERS_AFTER=$(sql "select id, status, date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') confirmed_at from orders where id in (${ORDER_A}, ${ORDER_B}) order by id")
RECORDS_A=$(records_for "${ORDER_A}")
RECORDS_B=$(records_for "${ORDER_B}")
KCAT=$(kcat -b "${BROKER}" -C -t "${TOPIC}" -o beginning -e -q -f 'partition=%p offset=%o key=%k\n%s\n')
GROUP_OFFSETS=$(kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group "${GROUP}" 2>/dev/null \
  | awk 'NR>1 && NF {print $2, $3, $4, $5, $6}')
OPS_STATUS=$(sql "select status, count(*) rows_, min(created_at) oldest_created, max(attempt_count) max_attempts from order_event_outbox group by status")
OPS_WAIT=$(sql "select timestampdiff(second, min(created_at), utc_timestamp(6)) longest_pending_wait_s from order_event_outbox where status = 'PENDING'")
OPS_BLOCKED=$(sql "select event_id, order_id, attempt_count, last_error from order_event_outbox where status = 'BLOCKED' order by id")
OPS_UNCONSUMED=$(sql "select o.event_id, o.published_at from order_event_outbox o left join order_confirmation_receipt r on r.event_id = o.event_id where o.status = 'PUBLISHED' and r.event_id is null")

same() { [ "$1" = "$2" ] && echo "같음" || echo "다름"; }

cat >"${RESULT}" <<MD
# W6 누락 재현과 W7 복구 결과 (EXP-014)

- 실행: \`bash docs/week7/recovery/run.sh\`, W6 \`${W6_REV}\`(${W6_COMMIT}), W7 \`${W7_COMMIT}\`, $(date -u +%Y-%m-%dT%H:%M:%SZ)
- topic \`${TOPIC}\`(partition 3), group \`${GROUP}\`, MySQL 컨테이너 \`${MYSQL}\` 는 실행마다 새로 만들고 실행이 끝나면 지운다. 이전 실행의 DB 를 쓰지 않는다
- W6 api, W6 streamer, PG fixture 를 프로세스로 띄웠다. 주문 B 는 W6 에서 발행과 소비까지 끝냈다. 주문 A 는 W6 api 를 \`linger.ms=60000\` 으로 다시 띄워 확정한 뒤, record 가 producer 버퍼에 있는 동안 \`kill -9\` 로 죽였다(${KILLED_AT}). 실패 지표에도 남지 않는 누락이다
- 그 뒤 같은 DB 에 W7 api 를 \`ddl-auto=update\` 로 띄우고 relay 를 켰다. streamer 는 W6 그대로 두었다
- 주문은 코트(3,000) × 2, 셔츠(1,000) × 1 이다. broker 는 \`kcat\` 으로 읽었다

## 준비: W6 에서 만든 두 주문

| | 주문 A | 주문 B |
|---|---|---|
| orderId | ${ORDER_A} | ${ORDER_B} |
| eventId | \`${EVENT_A}\` | \`${EVENT_B}\` |
| broker 의 record 수 | ${PREPARED_RECORDS_A} | ${PREPARED_RECORDS_B} |

\`\`\`
${PREPARED_ORDERS}
\`\`\`

\`\`\`
${PREPARED_ITEMS}
\`\`\`

\`\`\`
${PREPARED_PAYMENTS}
\`\`\`

재고(100 에서 시작, 두 주문이 각각 코트 2, 셔츠 1 을 예약)

\`\`\`
${PREPARED_STOCKS}
\`\`\`

구매 활동(주문 A 는 없어야 한다)

\`\`\`
${PREPARED_ACTIVITIES}
\`\`\`

PG fixture 호출 수(준비 단계 누적. create 가 C, transactions 가 T)

\`\`\`
${PG_PREPARED}
\`\`\`

## W7 로 바꾼 뒤의 테이블

W6 테이블

\`\`\`
${PREPARED_TABLES}
\`\`\`

W7 api 기동 뒤 테이블(\`order_event_outbox\` 만 늘어야 한다)

\`\`\`
${SWITCHED_TABLES}
\`\`\`

## 복구

| | 주문 A | 주문 B |
|---|---|---|
| 1 회차 | \`${RECOVERY1_A}\` | \`${RECOVERY1_B}\` |
| 2 회차 | \`${RECOVERY2_A}\` | \`${RECOVERY2_B}\` |
| broker 의 record 수(복구 뒤) | ${RECORDS_A} | ${RECORDS_B} |

outbox

\`\`\`
${OUTBOX}
\`\`\`

주문(확정 시각이 준비 때와 같아야 한다)

\`\`\`
${ORDERS_AFTER}
\`\`\`

## 복구 전후 비교

| 항목 | 복구 전 | 복구 뒤 | 비교 |
|---|---|---|---|
| PG fixture 호출 수 | \`${PG_BEFORE_RECOVERY}\` | \`${PG_AFTER}\` | $(same "${PG_BEFORE_RECOVERY}" "${PG_AFTER}") |
| 재고 | \`$(echo ${STOCKS_BEFORE_RECOVERY})\` | \`$(echo ${STOCKS_AFTER})\` | $(same "${STOCKS_BEFORE_RECOVERY}" "${STOCKS_AFTER}") |
| 주문 B 의 구매 활동(행 id, 수량, 수정 시각) | \`$(echo ${ACTIVITY_B_BEFORE})\` | \`$(echo ${ACTIVITY_B_AFTER})\` | $(same "${ACTIVITY_B_BEFORE}" "${ACTIVITY_B_AFTER}") |

## streamer: 처리 기록, 구매 활동, 보류

\`\`\`
${RECEIPTS}
\`\`\`

\`\`\`
${ACTIVITIES}
\`\`\`

보류 기록 수: ${HOLDS}

## broker (kcat)

\`\`\`
${KCAT}
\`\`\`

committed offset (TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG)

\`\`\`
${GROUP_OFFSETS}
\`\`\`

## 운영 SQL (design 7.1)

상태별 건수

\`\`\`
${OPS_STATUS}
\`\`\`

PENDING 최장 대기 시간

\`\`\`
${OPS_WAIT}
\`\`\`

BLOCKED

\`\`\`
${OPS_BLOCKED:-(없음)}
\`\`\`

PUBLISHED 인데 처리 기록이 없는 사건

\`\`\`
${OPS_UNCONSUMED:-(없음)}
\`\`\`
MD

step "끝. 결과: ${RESULT}, 로그: ${WORK}"
