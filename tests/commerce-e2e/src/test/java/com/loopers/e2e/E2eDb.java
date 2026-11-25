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

    /** 주문의 결제 기록: status·payment_attempt_key·transaction_key·total_amount·paid_amount·payment_started_at·confirmed_at (시각은 저장 문자열). */
    public Map<String, Object> orderPayment(Long orderId) {
        return jdbc.queryForMap("select id, status, payment_attempt_key, transaction_key, total_amount, paid_amount, "
            + "cast(payment_started_at as char) payment_started_at, cast(confirmed_at as char) confirmed_at from orders where id = ?", orderId);
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

    /** 구매 활동 (product_id, quantity, confirmed_at 저장 문자열), product_id 순. */
    public List<Map<String, Object>> activityWithTimeOfOrder(Long orderId) {
        return jdbc.queryForList("select product_id, quantity, cast(confirmed_at as char) confirmed_at from product_purchase_activity "
            + "where order_id = ? order by product_id", orderId);
    }

    public int activityCount() {
        return jdbc.queryForObject("select count(*) from product_purchase_activity", Integer.class);
    }

    /** snapshot 입력 (order_id, product_id, quantity), 정렬. */
    public List<List<Long>> rankingInputs(String snapshotId) {
        return jdbc.query("select order_id, product_id, quantity from ranking_snapshot_input where snapshot_id = ? order by order_id, product_id",
            (rs, n) -> List.of(rs.getLong("order_id"), rs.getLong("product_id"), rs.getLong("quantity")), snapshotId);
    }

    public Map<String, Object> rankingSnapshot(String snapshotId) {
        return jdbc.queryForMap("select * from ranking_snapshot where snapshot_id = ?", snapshotId);
    }

    /** 완성 ranking (product_id, score, rank_no), rank 순. */
    public List<List<Long>> ranking(String snapshotId) {
        return jdbc.query("select product_id, score, rank_no from weekly_ranking where snapshot_id = ? order by rank_no",
            (rs, n) -> List.of(rs.getLong("product_id"), rs.getLong("score"), rs.getLong("rank_no")), snapshotId);
    }

    /** 소비 완료 기록 (id, event_id). */
    public List<Map<String, Object>> processedOfOrder(Long orderId) {
        return jdbc.queryForList("select id, event_id from processed_order_confirmation where order_id = ? order by id", orderId);
    }
}
