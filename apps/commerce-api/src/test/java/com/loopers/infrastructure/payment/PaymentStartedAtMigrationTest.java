package com.loopers.infrastructure.payment;

import com.loopers.utils.DatabaseCleanUp;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트 스키마는 엔티티로 만들어 started_at 열·인덱스가 이미 있다. 열 추가 전 운영 DB 모양을 재현하려고 둘을 지운 뒤
// 스크립트를 실행한다. 같은 컨텍스트의 다른 테스트가 원래 스키마를 보도록 끝나면 엔티티 모양으로 되돌린다.
@SpringBootTest
class PaymentStartedAtMigrationTest {
    private static final String SCRIPT = "sql/add-payment-started-at.sql";
    private static final Instant CREATED_AT = Instant.parse("2026-10-21T00:00:00Z");

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp cleanup;
    private List<Map<String, Object>> entityColumn;

    @BeforeEach
    void prepareSchemaBeforeStartedAt() {
        entityColumn = startedAtColumn();
        assertThat(entityColumn).hasSize(1);
        jdbcTemplate.execute("alter table payment_records drop index idx_payment_records_result_started_at, drop column started_at");
        // 결제 대기(스케줄 있음), 확인 필요(스케줄 없음), 종결 PG, 포인트 기록
        payment(1, "PG", "PENDING", "PENDING");
        jdbcTemplate.update("insert into payment_lookup_schedules(order_id, next_lookup_at, actual_attempts, created_at, updated_at)"
            + " values (1, ?, 3, ?, ?)", Timestamp.from(CREATED_AT.plusSeconds(30)), Timestamp.from(CREATED_AT),
            Timestamp.from(CREATED_AT.plusSeconds(10)));
        payment(2, "PG", "PENDING", "NEEDS_REVIEW");
        payment(3, "PG", "SUCCESS", "SUCCESS");
        payment(4, "POINT", "SUCCESS", null);
    }

    @AfterEach
    void clean() {
        if (startedAtColumn().isEmpty()) {
            jdbcTemplate.execute("alter table payment_records add column started_at datetime(6)");
        }
        if (indexColumns().isEmpty()) {
            jdbcTemplate.execute("create index idx_payment_records_result_started_at on payment_records (result, started_at)");
        }
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("스크립트는 엔티티와 같은 시작 시각 열·인덱스를 만들고 스케줄이 있는 결제 대기만 스케줄 생성 시각으로 채운다")
    void addsColumnAndBackfillsPendingFromSchedule() {
        run();

        assertThat(startedAtColumn()).isEqualTo(entityColumn);
        assertThat(indexColumns()).containsExactly("result", "started_at");
        assertThat(startedAtByOrderId(1)).isEqualTo(CREATED_AT);
        assertThat(startedAtByOrderId(2)).isNull();
        assertThat(startedAtByOrderId(3)).isNull();
        assertThat(startedAtByOrderId(4)).isNull();
    }

    @Test
    @DisplayName("스크립트를 다시 실행해도 오류 없이 열·인덱스·채운 값이 한 번 실행한 결과와 같다")
    void rerunKeepsSameResult() {
        run();
        jdbcTemplate.update("update payment_lookup_schedules set created_at = ? where order_id = 1",
            Timestamp.from(CREATED_AT.plusSeconds(99)));

        run();

        assertThat(startedAtColumn()).isEqualTo(entityColumn);
        assertThat(indexColumns()).containsExactly("result", "started_at");
        assertThat(startedAtByOrderId(1)).isEqualTo(CREATED_AT);
    }

    private void payment(long orderId, String method, String result, String providerStatus) {
        jdbcTemplate.update("insert into payment_records(order_id, method, amount, result, provider_status) values (?, ?, 1000, ?, ?)",
            orderId, method, result, providerStatus);
    }

    private void run() {
        new ResourceDatabasePopulator(new ClassPathResource(SCRIPT)).execute(dataSource);
    }

    private List<Map<String, Object>> startedAtColumn() {
        return jdbcTemplate.queryForList("select column_type, is_nullable from information_schema.columns"
            + " where table_schema = database() and table_name = 'payment_records' and column_name = 'started_at'");
    }

    private List<String> indexColumns() {
        return jdbcTemplate.queryForList("select column_name from information_schema.statistics where table_schema = database()"
            + " and table_name = 'payment_records' and index_name = 'idx_payment_records_result_started_at' order by seq_in_index",
            String.class);
    }

    private Instant startedAtByOrderId(long orderId) {
        Timestamp value = jdbcTemplate.queryForObject("select started_at from payment_records where order_id = ?", Timestamp.class,
            orderId);
        return value == null ? null : value.toInstant();
    }
}
