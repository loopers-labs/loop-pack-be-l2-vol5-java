package com.loopers.domains.order.out;

import com.loopers.utils.DatabaseCleanUp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 운영 경계: 관찰 SQL 파일의 이름 붙인 조회 5개를 실제 MySQL에서 실행해, 준비한 행 상태에 맞는 값을 돌려주는지 본다.
// 영수증 테이블(processed_order_confirmation)은 streamer 엔티티라 api 테스트 스키마에 없다. 조회에 쓰는 열만 가진 테이블을 이 테스트가 만들고 지운다.
@SpringBootTest
class OutboxObservationSqlTest {
    private static final String SCRIPT = "sql/outbox-observation.sql";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private Map<String, String> queries;

    @BeforeEach
    void prepare() throws IOException {
        queries = namedQueries(new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute("create table if not exists processed_order_confirmation (id bigint not null auto_increment, event_id varchar(255)"
            + " not null, order_id bigint not null, processed_at datetime(6) not null, primary key (id))");
        insert(801, "PENDING", 1, 0, "TimeoutException: broker down", "- INTERVAL 300 SECOND", "+ INTERVAL 1 SECOND");
        insert(802, "PENDING", 0, 0, null, "- INTERVAL 60 SECOND", "- INTERVAL 60 SECOND");
        insert(803, "BLOCKED", 5, 5, "RecordTooLargeException: too large", "- INTERVAL 600 SECOND", "- INTERVAL 10 MINUTE");
        insert(804, "PUBLISHED", 1, 0, "TimeoutException: old", "- INTERVAL 900 SECOND", "- INTERVAL 10 MINUTE");
        insert(805, "PENDING", 3, 0, "NetworkException: stale", "- INTERVAL 3 HOUR", "- INTERVAL 2 HOUR");
        jdbc.update("insert into processed_order_confirmation(event_id, order_id, processed_at) values (?, 804, utc_timestamp(6))",
            eventId(804));
    }

    @AfterEach
    void clean() {
        jdbc.execute("drop table if exists processed_order_confirmation");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("관찰 SQL 5개는 PENDING 3(1시간 전 실패 1 포함)·BLOCKED 1·PUBLISHED 1(영수증 있음) 상태에서 PENDING 수·가장 오래된 PENDING 나이·BLOCKED 목록·최근 1시간 실패 이유·주문별 발행·소비 상태를 돌려준다")
    void observationQueriesReflectPreparedRows() {
        assertThat(queries).containsOnlyKeys("pending_count", "oldest_pending_age", "blocked_rows", "recent_failure_reasons",
            "order_delivery_state");

        assertThat(jdbc.queryForObject(queries.get("pending_count"), Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject(queries.get("oldest_pending_age"), Long.class)).isBetween(10_800L, 10_830L);
        assertThat(jdbc.queryForList(queries.get("blocked_rows"))).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("event_id", eventId(803))
            .containsEntry("order_id", 803L)
            .containsEntry("attempt_count", 5)
            .containsEntry("row_failure_count", 5)
            .containsEntry("last_error", "RecordTooLargeException: too large"));
        assertThat(jdbc.queryForList(queries.get("recent_failure_reasons")))
            .extracting(row -> row.get("reason") + "/" + row.get("status") + "/" + row.get("row_count"))
            .containsExactly("RecordTooLargeException/BLOCKED/1", "TimeoutException/PENDING/1")
            .noneMatch(reason -> reason.startsWith("NetworkException"));
        assertThat(jdbc.queryForMap(queries.get("order_delivery_state"), 804L))
            .containsEntry("event_id", eventId(804)).containsEntry("outbox_status", "PUBLISHED").containsEntry("consumed", 1L);
        assertThat(jdbc.queryForMap(queries.get("order_delivery_state"), 801L))
            .containsEntry("event_id", eventId(801)).containsEntry("outbox_status", "PENDING").containsEntry("consumed", 0L);
    }

    private void insert(long orderId, String status, int attemptCount, int rowFailureCount, String lastError, String createdOffset,
        String nextAttemptOffset) {
        jdbc.update("insert into order_outbox(event_id, order_id, event_type, schema_version, message_key, payload, status,"
                + " attempt_count, row_failure_count, last_error, next_attempt_at, created_at, published_at) values (?, ?,"
                + " 'OrderConfirmed', 1, ?, '{}', ?, ?, ?, ?, utc_timestamp(6) " + nextAttemptOffset + ", utc_timestamp(6) "
                + createdOffset + ", " + ("PUBLISHED".equals(status) ? "utc_timestamp(6)" : "null") + ")",
            eventId(orderId), orderId, String.valueOf(orderId), status, attemptCount, rowFailureCount, lastError);
    }

    // "-- name: 이름" 줄부터 다음 이름 줄 전까지가 조회 하나다. 주석 줄과 끝의 세미콜론은 뺀다.
    private static Map<String, String> namedQueries(String script) {
        Map<String, String> named = new LinkedHashMap<String, String>();
        String name = null;
        StringBuilder body = new StringBuilder();
        for (String line : script.split("\n")) {
            if (line.startsWith("-- name:")) {
                if (name != null) {
                    named.put(name, finish(body));
                }
                name = line.substring("-- name:".length()).trim();
                body = new StringBuilder();
            } else if (name != null && !line.startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        if (name != null) {
            named.put(name, finish(body));
        }
        return named;
    }

    private static String finish(StringBuilder body) {
        String sql = body.toString().trim();
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
    }

    private static String eventId(long orderId) {
        return "order-confirmed:v1:" + orderId;
    }
}
