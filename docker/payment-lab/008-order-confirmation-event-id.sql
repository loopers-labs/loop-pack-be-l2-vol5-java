-- 기존 주문 확정 기록을 보존하면서 신규 PG 확정의 사건 UUID를 저장한다. 한 번만 적용한다.
USE loopers;
ALTER TABLE order_records
    ADD COLUMN confirmation_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT uk_order_records_confirmation_event_id UNIQUE (confirmation_event_id);
