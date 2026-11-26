package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRefundCaseCounter implements RefundCaseCounter {
    private final JdbcTemplate jdbcTemplate;

    public JdbcRefundCaseCounter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Long> countByStatus() {
        return Map.of();
    }
}
