package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.UnresolvedPaymentFinder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 대상 조건은 JdbcUnresolvedPaymentCounter와 같다(orders 상태 인덱스 → payment_records order_id 고유 키).
// schedule은 order_id 기본 키, 이상은 (order_id, kind) 고유 키 선두로 주문마다 붙인다.
// 시각 열은 JPA가 UTC 벽시계로 저장하므로(jdbc.time_zone UTC) JVM 시간대를 타는 Timestamp 대신 UTC LocalDateTime으로 읽고 쓴다.
@Repository
public class JdbcUnresolvedPaymentFinder implements UnresolvedPaymentFinder {
    private static final String FIND_SQL = """
        select o.id, p.provider_order_id, p.provider_id, p.provider_status, p.started_at,
               s.actual_attempts, s.next_lookup_at, a.kind, a.last_summary, a.last_seen_at
        from orders o
        join payment_records p on p.order_id = o.id
        left join payment_lookup_schedules s on s.order_id = o.id
        left join payment_anomalies a on a.id = (
            select latest.id from payment_anomalies latest
            where latest.order_id = o.id and latest.resolved_at is null
            order by latest.last_seen_at desc, latest.id desc
            limit 1)
        where o.status = 'PAYMENT_PENDING' and p.method = 'PG'
          and p.provider_status in (%s) and p.started_at <= ?
        order by p.started_at, o.id
        limit ? offset ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUnresolvedPaymentFinder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Row> find(Set<String> providerStatuses, Instant startedAtOrBefore, long offset, int limit) {
        String sql = FIND_SQL.formatted(String.join(", ", Collections.nCopies(providerStatuses.size(), "?")));
        List<Object> arguments = new ArrayList<>(providerStatuses);
        arguments.add(LocalDateTime.ofInstant(startedAtOrBefore, ZoneOffset.UTC));
        arguments.add(limit);
        arguments.add(offset);
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> row(resultSet), arguments.toArray());
    }

    private static Row row(ResultSet resultSet) throws SQLException {
        int attempts = resultSet.getInt("actual_attempts");
        Integer lookupAttempts = resultSet.wasNull() ? null : attempts;
        return new Row(resultSet.getLong("id"), resultSet.getString("provider_order_id"), resultSet.getString("provider_id"),
            resultSet.getString("provider_status"), instant(resultSet, "started_at"), lookupAttempts,
            instant(resultSet, "next_lookup_at"), resultSet.getString("kind"), resultSet.getString("last_summary"),
            instant(resultSet, "last_seen_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        LocalDateTime value = resultSet.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
