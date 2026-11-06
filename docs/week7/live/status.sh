#!/usr/bin/env bash
# 지금 상태 한눈에: Outbox 상태별 수 · 최근 Outbox 10행(broker 위치·소비 완료·구매 활동) · BLOCKED · relay 잠금 · consumer lag · PG 호출 수
q() { docker exec -i docker-mysql-1 mysql -uapplication -papplication --default-character-set=utf8mb4 -t loopers_w7 2>/dev/null; }
echo "== Outbox 상태별 =="; echo "SELECT status, COUNT(*) cnt FROM order_outbox GROUP BY status;" | q
echo "== 최근 Outbox 10행 (주문 → Outbox → broker → 소비 완료 → 구매 활동) =="
q <<'SQL'
SELECT o.order_id, LEFT(o.event_id, 8) AS event, o.status, o.attempt_count AS att,
       IF(o.status = 'PUBLISHED', NULL, CONVERT_TZ(o.next_attempt_at, '+00:00', '+09:00')) AS next_or_lease_kst,
       o.published_partition AS p, o.published_offset AS off, c.id IS NOT NULL AS consumed,
       (SELECT GROUP_CONCAT(CONCAT(a.product_id, 'x', a.quantity) ORDER BY a.product_id) FROM product_purchase_activity a WHERE a.event_id = o.event_id) AS activity,
       LEFT(o.last_error, 60) AS last_error
FROM order_outbox o LEFT JOIN consumed_order_confirmation c ON c.event_id = o.event_id
ORDER BY o.id DESC LIMIT 10;
SQL
echo "== relay 잠금 =="; echo "SELECT locked_by, CONVERT_TZ(locked_at,'+00:00','+09:00') locked_at_kst, lock_until > UTC_TIMESTAMP(3) held_now FROM shedlock;" | q
echo "== consumer lag =="; docker exec w7-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server w7-kafka-1:9092 --describe --all-groups 2>/dev/null | awk '/GROUP/&&!h{print;h=1} /order-events/' | awk '{print $2, $3, $4, $5, $6}' | column -t
echo "== PG 호출 수 =="; curl -s localhost:8082/_stats; echo
