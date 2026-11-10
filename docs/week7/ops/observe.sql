-- W7 Outbox 관찰 SQL (docs/week7/operations.md). 읽기 전용이다.
-- 시각 컬럼은 UTC로 저장된다(jpa.yml hibernate.jdbc.time_zone: UTC) → 비교는 NOW()가 아니라 UTC_TIMESTAMP(6).
-- commerce-api와 commerce-streamer가 같은 DB(loopers)를 쓰는 전제다 — O-6은 두 앱의 테이블을 함께 본다.

-- O-1 상태별 개수와 가장 오래된 행
SELECT status, COUNT(*) AS cnt, MIN(created_at) AS oldest_created_utc
FROM order_outbox GROUP BY status ORDER BY FIELD(status, 'PENDING', 'SENDING', 'BLOCKED', 'PUBLISHED');

-- O-2 backlog: 지금 보낼 차례인데 아직 broker 확인이 없는 행 (relay 조회 조건과 같다, idx_order_outbox_due)
SELECT COUNT(*) AS due_cnt,
       TIMESTAMPDIFF(SECOND, MIN(next_attempt_at), UTC_TIMESTAMP(6)) AS oldest_due_wait_sec
FROM order_outbox
WHERE status IN ('PENDING', 'SENDING') AND next_attempt_at <= UTC_TIMESTAMP(6);

-- O-3 재시도 중: 실패한 적이 있고 아직 BLOCKED는 아닌 행
SELECT event_id, order_id, status, attempt_count, next_attempt_at AS next_attempt_utc, LEFT(last_error, 120) AS last_error
FROM order_outbox
WHERE status IN ('PENDING', 'SENDING') AND attempt_count > 0
ORDER BY id;

-- O-4 BLOCKED 목록 (사람이 원인을 고친 뒤 재개할 대상)
SELECT event_id, order_id, attempt_count, updated_at AS blocked_at_utc, LEFT(last_error, 200) AS last_error
FROM order_outbox WHERE status = 'BLOCKED' ORDER BY id;

-- O-5 lease가 지난 SENDING: relay가 다음 회차에 다시 보낼 행. 몇 분 넘게 남아 있으면 relay가 돌지 않는 것이다 (O-7 확인)
SELECT event_id, order_id, next_attempt_at AS lease_until_utc,
       TIMESTAMPDIFF(SECOND, next_attempt_at, UTC_TIMESTAMP(6)) AS expired_sec
FROM order_outbox WHERE status = 'SENDING' AND next_attempt_at < UTC_TIMESTAMP(6) ORDER BY id;

-- O-6 PUBLISHED ≠ 소비 완료: broker는 받았지만(PUBLISHED) consumer 완료 기록이 아직 없는 행.
--     발행 직후 몇 초는 정상이다. 1분 넘게 남으면 consumer 지연·실패(DLT)를 본다.
SELECT o.event_id, o.order_id, o.published_partition AS p, o.published_offset AS off, o.published_at AS published_utc
FROM order_outbox o
LEFT JOIN consumed_order_confirmation c ON c.event_id = o.event_id
WHERE o.status = 'PUBLISHED' AND c.id IS NULL AND o.published_at < UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE
ORDER BY o.id;

-- O-7 relay 작업 잠금 (ShedLock). lock_until이 과거면 잠금이 풀려 있다 = 다음 tick에 누군가 얻는다.
--     locked_at이 계속 갱신되지 않으면 relay가 돌지 않는다.
SELECT name, locked_by, locked_at, lock_until, lock_until > UTC_TIMESTAMP(3) AS held_now FROM shedlock;

-- O-8 한 사건 추적: 주문 → Outbox → broker 위치 → 소비 완료 → 구매 활동 (eventId로)
SELECT o.order_id, o.event_id, o.status, o.attempt_count, o.published_partition AS p, o.published_offset AS off,
       c.id IS NOT NULL AS consumed,
       (SELECT GROUP_CONCAT(CONCAT(a.product_id, 'x', a.quantity) ORDER BY a.product_id)
          FROM product_purchase_activity a WHERE a.event_id = o.event_id) AS activity
FROM order_outbox o
LEFT JOIN consumed_order_confirmation c ON c.event_id = o.event_id
WHERE o.event_id = 'EVENT_ID';  -- 찾을 eventId로 바꾼다 (사용자 변수 @x는 collation이 고정돼 DB에 따라 비교가 거절된다)
