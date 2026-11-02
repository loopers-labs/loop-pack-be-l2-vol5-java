#!/usr/bin/env bash
# 전체 흐름의 구간별 시간을 표로 본다 — 확정 요청 → PG 승인 callback → 주문 확정 → broker 저장(send 콜백 성공을 기록한 시각) → streamer 소비 기록 저장.
# 시각은 DB 행의 값이다: payments.created_at · orders.confirmed_at(확정 TX 안) · event_outbox.published_at(결과 executor) · consumed_order_confirmation.created_at(소비 TX 안).
# 준비: README 0 ~ 1절 (실험 DB · pg-sim auto · api · streamer). 실행: bash docs/week6/k6/latency.sh [주문 수=10]
set -euo pipefail
N=${1:-10}
API=http://127.0.0.1:8080
DB="docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_w6"
START_ID=$($DB -N -e "SELECT COALESCE(MAX(id), 0) + 1 FROM orders" 2>/dev/null)
for i in $(seq 1 "$N"); do
  user=$(( (i % 1000) + 1 )); product=$(( (i % 10) + 1 ))
  id=$(curl -s -XPOST $API/api/v1/orders -H 'Content-Type: application/json' -H "X-USER-ID: $user" \
    -d "{\"items\":[{\"productId\":$product,\"quantity\":1}]}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')
  curl -s -o /dev/null -XPOST $API/api/v1/orders/$id/confirm -H 'Content-Type: application/json' -H "X-USER-ID: $user" \
    -H "Idempotency-Key: latency-$id-$(date +%s%N)" -d '{"cardType":"SAMSUNG","cardNo":"1234-5678-9814-1451"}'
  sleep 1
done
sleep 4
$DB -t 2>/dev/null <<SQL
SELECT o.id AS orderId,
       CONCAT(e.published_partition, '@', e.published_offset) AS record,
       ROUND(TIMESTAMPDIFF(MICROSECOND, p.created_at, o.confirmed_at) / 1000) AS 'confirm요청→확정(ms)',
       ROUND(TIMESTAMPDIFF(MICROSECOND, o.confirmed_at, e.published_at) / 1000) AS '확정→broker저장(ms)',
       ROUND(TIMESTAMPDIFF(MICROSECOND, e.published_at, c.created_at) / 1000) AS 'broker→소비저장(ms)',
       ROUND(TIMESTAMPDIFF(MICROSECOND, o.confirmed_at, c.created_at) / 1000) AS '확정→소비(ms)'
FROM orders o
JOIN payments p ON p.order_id = o.id
LEFT JOIN event_outbox e ON e.aggregate_id = o.id
LEFT JOIN consumed_order_confirmation c ON c.order_id = o.id
WHERE o.id >= $START_ID
ORDER BY o.id;
SQL
