package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.UnresolvedPaymentCounter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// orders는 idx_orders_status_confirmed_at(status 선두)로 PAYMENT_PENDING만 고르고, payment_records는 uk_payment_records_order_id로 붙는다.
@Repository
public class JdbcUnresolvedPaymentCounter implements UnresolvedPaymentCounter {
    private static final String COUNT_SQL = """
        select p.provider_status, count(*)
        from orders o
        join payment_records p on p.order_id = o.id
        where o.status = 'PAYMENT_PENDING' and p.method = 'PG'
        group by p.provider_status
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUnresolvedPaymentCounter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Long> countByProviderStatus() {
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query(COUNT_SQL, resultSet -> {
            counts.put(resultSet.getString(1), resultSet.getLong(2));
        });
        return counts;
    }
}
