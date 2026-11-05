-- 주문 최초 확정 시각 열·인덱스 추가 (설계 27절 D78)
-- ddl-auto를 쓰지 않는 환경에서 확정 이벤트 발행 코드를 배포하기 전에 실행한다. 열·인덱스 모양은 OrderJpaEntity와 같다.
-- MySQL 8에는 ADD COLUMN IF NOT EXISTS가 없어 information_schema에서 없을 때만 추가한다. 다시 실행해도 결과가 같다.
-- 이미 CONFIRMED인 옛 주문은 확정 시각을 알 수 없어 비워 두며 소급 발행하지 않는다.
SET @add_order_confirmed_at = (
    SELECT IF(COUNT(*) = 0, 'ALTER TABLE orders ADD COLUMN confirmed_at DATETIME(6) NULL', 'DO 0')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'confirmed_at'
);
PREPARE add_order_confirmed_at FROM @add_order_confirmed_at;
EXECUTE add_order_confirmed_at;
DEALLOCATE PREPARE add_order_confirmed_at;

-- 3b 대사가 확정 주문을 확정 시각 범위로 고른다.
SET @add_order_confirmed_at_index = (
    SELECT IF(COUNT(*) = 0, 'CREATE INDEX idx_orders_status_confirmed_at ON orders (status, confirmed_at)', 'DO 0')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND index_name = 'idx_orders_status_confirmed_at'
);
PREPARE add_order_confirmed_at_index FROM @add_order_confirmed_at_index;
EXECUTE add_order_confirmed_at_index;
DEALLOCATE PREPARE add_order_confirmed_at_index;
