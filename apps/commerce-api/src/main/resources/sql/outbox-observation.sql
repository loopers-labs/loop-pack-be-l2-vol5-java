-- 주문 확정 Outbox 관찰 조회 (설계 28절 운영 경계)
-- 읽기 전용이다. 필요한 조회를 골라 실행한다. 각 조회는 "-- name:" 줄로 구분한다.
-- 시각 열은 UTC로 저장되므로 지금 시각도 UTC_TIMESTAMP로 비교한다.

-- name: pending_count
-- 보낼 차례를 기다리거나 재시도 중인 행 수. 계속 늘면 relay가 밀리거나 broker 장애다.
SELECT COUNT(*) AS pending_count
FROM order_outbox
WHERE status = 'PENDING';

-- name: oldest_pending_age
-- 가장 오래 기다린 PENDING 행이 기록된 뒤 지난 초. 확정 뒤 broker 도착까지 가장 긴 지연이다(없으면 0).
SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), UTC_TIMESTAMP(6)), 0) AS oldest_pending_age_seconds
FROM order_outbox
WHERE status = 'PENDING';

-- name: blocked_rows
-- 행 탓 실패가 쌓여 멈춘 행. 원인을 고친 뒤 로컬 진입점(--outbox.resume=<eventId>)으로 하나씩 재개한다.
SELECT event_id, order_id, attempt_count, row_failure_count, last_error
FROM order_outbox
WHERE status = 'BLOCKED'
ORDER BY next_attempt_at, id;

-- name: recent_failure_reasons
-- 최근 1시간 실패 이유 분포. last_error의 원인 예외 이름별로 센다.
-- 실패 시각 열이 없어 next_attempt_at(실패 시각 + backoff, 최대 60초 뒤)으로 근사한다.
-- BLOCKED에서 재개한 행은 next_attempt_at이 재개 시각이고 last_error가 남아 있어, 새 실패가 없어도 여기에 다시 잡힌다.
SELECT SUBSTRING_INDEX(last_error, ':', 1) AS reason, status, COUNT(*) AS row_count
FROM order_outbox
WHERE status IN ('PENDING', 'BLOCKED')
  AND last_error IS NOT NULL
  AND next_attempt_at >= UTC_TIMESTAMP(6) - INTERVAL 1 HOUR
GROUP BY reason, status
ORDER BY row_count DESC, reason, status;

-- name: order_delivery_state
-- 주문 하나의 발행 상태와 소비 완료(영수증) 여부. ?에 주문 ID를 넣는다.
-- PENDING = 아직 broker 확인 없음, PUBLISHED = broker 확인, consumed = streamer가 처리 완료를 기록함.
-- api·streamer가 같은 DB일 때만 이 한 줄로 볼 수 있다. 운영에서 DB가 나뉘면 order_outbox와 processed_order_confirmation을 따로 본다.
SELECT o.event_id, o.status AS outbox_status, o.attempt_count, o.published_at, p.processed_at IS NOT NULL AS consumed
FROM order_outbox o
LEFT JOIN processed_order_confirmation p ON p.event_id = o.event_id
WHERE o.order_id = ?;
