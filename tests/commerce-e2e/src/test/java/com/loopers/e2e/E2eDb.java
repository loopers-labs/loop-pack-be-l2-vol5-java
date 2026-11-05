package com.loopers.e2e;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 앱 context 와 따로 여는 DB 연결. 확인은 매번 새 조회다 (앱의 영속성 컨텍스트·캐시를 거치지 않는다). 시각은 DB 에 저장된 문자 그대로 읽는다.
 */
public final class E2eDb {
    private final DriverManagerDataSource dataSource;
    private final JdbcTemplate jdbc;

    public E2eDb() {
        dataSource = new DriverManagerDataSource(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            System.getProperty("datasource.mysql-jpa.main.username"),
            System.getProperty("datasource.mysql-jpa.main.password"));
        jdbc = new JdbcTemplate(dataSource);
    }

    /** 배포 SQL 을 그대로 실행한다. */
    public void runScript(Path script) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 실행 실패 [" + script + "]", e);
        }
    }

    public boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = database() and table_name = ?", Integer.class, table);
        return count != null && count > 0;
    }

    /** 주문 행: status·confirmed_at·transaction_key (저장 문자열). */
    public Map<String, Object> order(Long orderId) {
        return jdbc.queryForMap(
            "select status, cast(confirmed_at as char) confirmed_at, transaction_key from orders where id = ?", orderId);
    }

    /** 주문의 재고 원장 (product_id, type, quantity), id 순. */
    public List<Map<String, Object>> ledgerOfOrder(Long orderId) {
        return jdbc.queryForList("select product_id, type, quantity from product_stock_ledger where order_id = ? order by id", orderId);
    }

    public int stockOf(Long productId) {
        return jdbc.queryForObject("select available from product_stock where product_id = ?", Integer.class, productId);
    }

    public long pointOf(Long userId) {
        return jdbc.queryForObject("select balance from point where user_id = ?", Long.class, userId);
    }

    /** 주문의 Outbox 행 (상태·시도·발행 위치), id 순. */
    public List<Map<String, Object>> outboxOfOrder(Long orderId) {
        return jdbc.queryForList("select id, event_id, message_key, payload, status, attempt_count, failure_count, published_partition, published_offset "
            + "from order_event_outbox where order_id = ? order by id", orderId);
    }

    public int outboxCount() {
        return jdbc.queryForObject("select count(*) from order_event_outbox", Integer.class);
    }

    /** 구매 활동 (id, product_id, quantity), product_id 순. */
    public List<Map<String, Object>> activityOfOrder(Long orderId) {
        return jdbc.queryForList("select id, product_id, quantity from product_purchase_activity where order_id = ? order by product_id", orderId);
    }

    /** 소비 완료 기록 (id, event_id). */
    public List<Map<String, Object>> processedOfOrder(Long orderId) {
        return jdbc.queryForList("select id, event_id from processed_order_confirmation where order_id = ? order by id", orderId);
    }
}
