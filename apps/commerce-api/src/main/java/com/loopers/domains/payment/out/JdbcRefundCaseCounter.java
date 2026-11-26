package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// (money_status, next_check_at) 인덱스의 선두 컬럼으로 돈의 상태별 수와 가장 오래된 환불 요청을 센다.
// 사람 담당 중 수는 인덱스 없이 센다. 대상 행이 적고 지표 갱신 주기로만 읽는다.
// 시각 열은 JPA가 UTC 벽시계로 저장하므로(jdbc.time_zone UTC) UTC LocalDateTime으로 읽는다.
@Repository
public class JdbcRefundCaseCounter implements RefundCaseCounter {
    private static final String COUNT_SQL = "select money_status, count(*) from payment_refund_cases group by money_status";
    private static final String PERSON_OPEN_SQL =
        "select count(*) from payment_refund_cases where owner = 'PERSON' and owner_closed_at is null";
    private static final String OLDEST_REQUEST_SQL =
        "select min(refund_requested_at) from payment_refund_cases where money_status = 'REFUND_REQUESTED'";

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefundCaseCounter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Snapshot snapshot() {
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query(COUNT_SQL, resultSet -> {
            counts.put(resultSet.getString(1), resultSet.getLong(2));
        });
        Long personOpen = jdbcTemplate.queryForObject(PERSON_OPEN_SQL, Long.class);
        LocalDateTime oldest = jdbcTemplate.queryForObject(OLDEST_REQUEST_SQL, LocalDateTime.class);
        Optional<Instant> oldestRequestedAt = Optional.ofNullable(oldest).map(value -> value.toInstant(ZoneOffset.UTC));
        return new Snapshot(counts, personOpen == null ? 0 : personOpen, oldestRequestedAt);
    }
}
