package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// (money_status, next_check_at) 인덱스의 선두 컬럼으로 돈의 상태별 수를 센다.
@Repository
public class JdbcRefundCaseCounter implements RefundCaseCounter {
    private static final String COUNT_SQL = "select money_status, count(*) from payment_refund_cases group by money_status";

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefundCaseCounter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    // 골격(w10s5b S7 Red): 사람 담당 중 수·가장 오래된 요청 시각은 Green에서 읽는다.
    public Snapshot snapshot() {
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query(COUNT_SQL, resultSet -> {
            counts.put(resultSet.getString(1), resultSet.getLong(2));
        });
        return new Snapshot(counts, 0, Optional.empty());
    }
}
