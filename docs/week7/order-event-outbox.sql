-- =====================================================================
-- 주문 이벤트 Outbox 테이블 (MySQL, TB-14)
--
-- order_event_outbox 를 만든다. 행은 넣지 않는다. 확정 트랜잭션이 새 확정마다 한 행을 남기고,
-- W6 직접 발행에서 빠진 확정은 지정한 주문만 복구 경로(OrderConfirmedBackfill)가 저장된 확정 사실로 한 행씩 넣는다.
-- 이전 확정 주문 전체를 소급하지 않는다.
--
-- 순서
--   운영(ddl-auto: none)은 새 앱(Outbox 기록)을 배포하기 전에 실행한다. 테이블이 없으면 새 앱의 첫 확정이 insert 에서 실패해 rollback 된다.
--   local 프로필은 ddl-auto: update 가 같은 테이블을 만든다.
--
-- 실행
--   docker exec -i docker-mysql-1 mysql -uroot -proot loopers < docs/week7/order-event-outbox.sql
--
-- 이미 있으면 그대로 둔다 (IF NOT EXISTS). 행을 지우지 않는다.
-- =====================================================================

SET NAMES utf8mb4;

-- 엔티티(OrderEventOutboxModel)와 같은 이름·컬럼
CREATE TABLE IF NOT EXISTS order_event_outbox (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    event_id            VARCHAR(100) NOT NULL,
    order_id            BIGINT       NOT NULL,
    event_type          VARCHAR(50)  NOT NULL,
    schema_version      INT          NOT NULL,
    message_key         VARCHAR(64)  NOT NULL,
    payload             TEXT         NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    attempt_count       INT          NOT NULL,
    failure_count       INT          NOT NULL,
    next_attempt_at     DATETIME(6)  NOT NULL,
    last_error          VARCHAR(500) NULL,
    published_at        DATETIME(6)  NULL,
    published_partition INT          NULL,
    published_offset    BIGINT       NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    deleted_at          DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_order_event_outbox_event UNIQUE (event_id),
    INDEX ix_order_event_outbox_due (status, next_attempt_at, id)
);
