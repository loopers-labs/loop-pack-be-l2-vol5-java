package com.loopers.support;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PurchaseActivityTables {

    private final JdbcTemplate jdbcTemplate;

    public PurchaseActivityTables(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long confirmations() {
        return count("processed_order_confirmation");
    }

    public long activities() {
        return count("product_purchase_activity");
    }

    public long anomalies() {
        return count("order_event_anomaly");
    }

    public long quantityOf(long productId) {
        Long quantity = jdbcTemplate.queryForObject(
            "select coalesce(sum(quantity), 0) from product_purchase_activity where product_id = ?", Long.class, productId);
        return quantity == null ? 0 : quantity;
    }

    public List<Map<String, Object>> activitiesOf(long orderId) {
        return jdbcTemplate.queryForList(
            "select product_id, quantity, confirmed_at from product_purchase_activity where order_id = ? order by product_id",
            orderId);
    }

    public List<Map<String, Object>> anomalyRows() {
        return jdbcTemplate.queryForList(
            "select kind, event_id, order_id, topic, partition_no, offset_no, raw_payload from order_event_anomaly order by id");
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return count == null ? 0 : count;
    }
}
