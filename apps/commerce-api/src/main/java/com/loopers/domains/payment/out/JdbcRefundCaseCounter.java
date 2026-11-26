package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// (status, next_check_at) 인덱스의 선두 컬럼으로 상태별 수를 센다.
@Repository
public class JdbcRefundCaseCounter implements RefundCaseCounter {
    private static final String COUNT_SQL = "select status, count(*) from payment_refund_cases group by status";

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefundCaseCounter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query(COUNT_SQL, resultSet -> {
            counts.put(resultSet.getString(1), resultSet.getLong(2));
        });
        return counts;
    }
}
