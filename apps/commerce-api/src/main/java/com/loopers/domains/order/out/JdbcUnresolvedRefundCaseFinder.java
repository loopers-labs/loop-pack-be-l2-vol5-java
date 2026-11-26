package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.UnresolvedRefundCaseFinder;
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

// 사람 조치 대상은 적은 행이라 돈의 상태 필터는 (money_status, next_check_at) 인덱스의 선두만 쓰고 열린 시각은 정렬로 처리한다.
// 사람 담당 중 필터는 인덱스 없이 거른다(대상 행이 적다).
// 시각 열은 JPA가 UTC 벽시계로 저장하므로(jdbc.time_zone UTC) UTC LocalDateTime으로 읽고 쓴다.
@Repository
public class JdbcUnresolvedRefundCaseFinder implements UnresolvedRefundCaseFinder {
    private static final String FIND_SQL = """
        select order_id, provider_order_id, provider_id, amount, money_status, refund_actor, owner, owner_reason,
               owner_changed_at, owner_closed_at, prior_provider_status, transaction_key is not null as has_transaction_key,
               opened_at, handoff_at, promise_at, check_attempts, next_check_at, refund_requested_at, concluded_at
        from payment_refund_cases
        where opened_at <= ?%s
        order by opened_at, order_id
        limit ? offset ?
        """;
    private static final String PERSON_OPEN_CONDITION = " and owner = 'PERSON' and owner_closed_at is null";

    private final JdbcTemplate jdbcTemplate;

    public JdbcUnresolvedRefundCaseFinder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Row> find(boolean personOpenOnly, Set<String> moneyStatuses, Instant openedAtOrBefore, long offset,
        int limit) {
        StringBuilder conditions = new StringBuilder();
        List<Object> arguments = new ArrayList<>();
        arguments.add(LocalDateTime.ofInstant(openedAtOrBefore, ZoneOffset.UTC));
        if (personOpenOnly) {
            conditions.append(PERSON_OPEN_CONDITION);
        }
        if (!moneyStatuses.isEmpty()) {
            conditions.append(" and money_status in (")
                .append(String.join(", ", Collections.nCopies(moneyStatuses.size(), "?")))
                .append(")");
            arguments.addAll(moneyStatuses);
        }
        arguments.add(limit);
        arguments.add(offset);
        return jdbcTemplate.query(FIND_SQL.formatted(conditions), (resultSet, rowNumber) -> row(resultSet),
            arguments.toArray());
    }

    private static Row row(ResultSet resultSet) throws SQLException {
        return new Row(resultSet.getLong("order_id"), resultSet.getString("provider_order_id"),
            resultSet.getString("provider_id"), resultSet.getLong("amount"), resultSet.getString("money_status"),
            resultSet.getString("refund_actor"), resultSet.getString("owner"), resultSet.getString("owner_reason"),
            instant(resultSet, "owner_changed_at"), instant(resultSet, "owner_closed_at"),
            resultSet.getString("prior_provider_status"), resultSet.getBoolean("has_transaction_key"),
            instant(resultSet, "opened_at"), instant(resultSet, "handoff_at"), instant(resultSet, "promise_at"),
            resultSet.getInt("check_attempts"), instant(resultSet, "next_check_at"), instant(resultSet, "refund_requested_at"),
            instant(resultSet, "concluded_at"), null, null, null);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        LocalDateTime value = resultSet.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
