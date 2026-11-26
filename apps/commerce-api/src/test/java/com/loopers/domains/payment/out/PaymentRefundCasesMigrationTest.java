package com.loopers.domains.payment.out;

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

// 5단계 한 칸 모델(status·reason) 테이블을 두 축 모델로 옮기는 로컬 이전 스크립트를 실제 MySQL로 본다(10주차 5b 계획 6절, S9).
// v3(10주차 5c)는 v2 뒤에 마지막 확인 세 칸을 더한다. 엔티티 모양은 v2 → v3 순서로 실행한 결과와 비교한다.
// 테스트 스키마는 엔티티로 만들어지므로 지우고 5단계 모양을 직접 만든 뒤 옛 값 행을 넣어 이전한다. 끝나면 엔티티가 만든 DDL로 되돌린다.
@SpringBootTest
class PaymentRefundCasesMigrationTest {
    private static final String SCRIPT = "sql/migrate-payment-refund-cases-v2.sql";
    private static final String SCRIPT_V3 = "sql/migrate-payment-refund-cases-v3.sql";
    private static final String TABLE = "payment_refund_cases";
    private static final String NEW_INDEX = "idx_payment_refund_cases_money_status_next_check";
    private static final String OLD_INDEX = "idx_payment_refund_cases_status_next_check";
    // 5단계(fb4f6f2) create-payment-refund-cases.sql의 테이블 모양이다.
    private static final String STAGE5_DDL = """
        CREATE TABLE payment_refund_cases (
            order_id BIGINT NOT NULL,
            provider_id VARCHAR(50) NOT NULL,
            provider_order_id VARCHAR(50) NOT NULL,
            amount BIGINT NOT NULL,
            status VARCHAR(30) NOT NULL,
            prior_provider_status VARCHAR(30) NULL,
            transaction_key VARCHAR(100) NULL,
            opened_at DATETIME(6) NOT NULL,
            handoff_at DATETIME(6) NOT NULL,
            promise_at DATETIME(6) NOT NULL,
            next_check_at DATETIME(6) NULL,
            check_attempts INT NOT NULL,
            refund_requested_at DATETIME(6) NULL,
            concluded_at DATETIME(6) NULL,
            reason VARCHAR(500) NULL,
            PRIMARY KEY (order_id),
            INDEX idx_payment_refund_cases_status_next_check (status, next_check_at)
        )""";
    private static final String NEXT = "2026-10-21 00:03:00.000000";
    private static final String REQUESTED = "2026-10-21 00:04:00.000000";
    private static final String CONCLUDED = "2026-10-21 00:05:00.000000";
    private static final String SELECT_STATE = """
        select order_id, money_status, refund_actor, owner, owner_reason,
               date_format(owner_changed_at, '%Y-%m-%d %H:%i:%s.%f') as owner_changed_at,
               owner_closed_at, owner_closed_by,
               date_format(next_check_at, '%Y-%m-%d %H:%i:%s.%f') as next_check_at,
               date_format(refund_requested_at, '%Y-%m-%d %H:%i:%s.%f') as refund_requested_at,
               date_format(concluded_at, '%Y-%m-%d %H:%i:%s.%f') as concluded_at, check_attempts
        from payment_refund_cases order by order_id
        """;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    private String entityDdl;
    private List<Map<String, Object>> entityColumns;

    @BeforeEach
    void createStage5Table() {
        entityDdl = jdbcTemplate.queryForMap("show create table " + TABLE).get("Create Table").toString();
        entityColumns = columns();
        assertThat(entityColumns).isNotEmpty();
        jdbcTemplate.execute("drop table " + TABLE);
        jdbcTemplate.execute(STAGE5_DDL);
        insert(1, "TRACKING", null, NEXT, null, null);
        insert(2, "REFUND_REQUESTED", null, NEXT, REQUESTED, null);
        insert(3, "REFUND_UNKNOWN", null, NEXT, REQUESTED, null);
        insert(4, "REFUNDED", null, null, REQUESTED, CONCLUDED);
        insert(5, "CLOSED_NO_CHARGE", null, null, null, CONCLUDED);
        insert(6, "HANDED_TO_HUMAN", "RESULT_REVERSED", null, null, CONCLUDED);
        insert(7, "HANDED_TO_HUMAN", "TRANSACTION_MISMATCH", null, null, CONCLUDED);
        insert(8, "HANDED_TO_HUMAN", "MULTIPLE_TRANSACTIONS", null, REQUESTED, CONCLUDED);
        insert(9, "HANDED_TO_HUMAN", "REFUND_REJECTED", null, REQUESTED, CONCLUDED);
        insert(10, "HANDED_TO_HUMAN", "HANDOFF_DEADLINE", null, REQUESTED, CONCLUDED);
        insert(11, "HANDED_TO_HUMAN", "HANDOFF_DEADLINE", null, null, CONCLUDED);
    }

    @AfterEach
    void restoreEntityTable() {
        jdbcTemplate.execute("drop table if exists " + TABLE);
        jdbcTemplate.execute(entityDdl);
    }

    @Test
    @DisplayName("옛 6값 status 행을 이전하면 표대로 돈의 상태·담당·환불 주체로 나뉘고 사람 담당 행은 결론을 비우고 조회를 다시 잡으며 테이블은 엔티티와 같은 모양이 된다")
    void migratesStage5RowsToTwoAxes() {
        run();

        assertSameAsEntity();
        assertThat(states()).containsExactly(
            state(1, "CHECKING", null, "AUTO", null, null, NEXT, null, null),
            state(2, "REFUND_REQUESTED", null, "AUTO", null, null, NEXT, REQUESTED, null),
            state(3, "REFUND_UNKNOWN", null, "AUTO", null, null, NEXT, REQUESTED, null),
            state(4, "REFUNDED", "SYSTEM", "AUTO", null, null, null, REQUESTED, CONCLUDED),
            state(5, "NO_CHARGE", null, "AUTO", null, null, null, null, CONCLUDED),
            state(6, "CHARGED", null, "PERSON", "RESULT_REVERSED", CONCLUDED, CONCLUDED, null, null),
            state(7, "CHARGED", null, "PERSON", "TRANSACTION_MISMATCH", CONCLUDED, CONCLUDED, null, null),
            state(8, "CHARGED", null, "PERSON", "MULTIPLE_TRANSACTIONS", CONCLUDED, CONCLUDED, REQUESTED, null),
            state(9, "CHARGED", null, "PERSON", "REFUND_REJECTED", CONCLUDED, CONCLUDED, REQUESTED, null),
            state(10, "REFUND_UNKNOWN", null, "PERSON", "HANDOFF_DEADLINE", CONCLUDED, CONCLUDED, REQUESTED, null),
            state(11, "CHECKING", null, "PERSON", "HANDOFF_DEADLINE", CONCLUDED, CONCLUDED, null, null));
    }

    @Test
    @DisplayName("이전 스크립트를 두 번 실행해도 오류 없이 한 번 실행한 결과와 같은 행·모양이다")
    void rerunKeepsSameResult() {
        run();
        assertSameAsEntity();
        List<Map<String, Object>> once = jdbcTemplate.queryForList(SELECT_STATE);

        run();

        assertSameAsEntity();
        assertThat(jdbcTemplate.queryForList(SELECT_STATE)).isEqualTo(once);
    }

    @Test
    @DisplayName("v2 → v3를 실행하면 기존 행의 마지막 확인 세 칸은 비어 있고 v3를 다시 실행해도 행·모양이 같다")
    void addsEmptyLastCheckColumns() {
        run();
        List<Map<String, Object>> once = jdbcTemplate.queryForList(SELECT_STATE);

        new ResourceDatabasePopulator(new ClassPathResource(SCRIPT_V3)).execute(dataSource);

        assertSameAsEntity();
        assertThat(jdbcTemplate.queryForList(SELECT_STATE)).isEqualTo(once);
        assertThat(jdbcTemplate.queryForObject("select count(*) from payment_refund_cases where last_check_at is null"
            + " and last_check_outcome is null and last_check_cause is null", Long.class)).isEqualTo(11);
    }

    private void assertSameAsEntity() {
        assertThat(columns()).isEqualTo(entityColumns);
        assertThat(indexColumns(NEW_INDEX)).containsExactly("money_status", "next_check_at");
        assertThat(indexColumns(OLD_INDEX)).isEmpty();
    }

    private void insert(long orderId, String status, String reason, String nextCheckAt, String refundRequestedAt,
        String concludedAt) {
        jdbcTemplate.update("insert into payment_refund_cases(order_id, provider_id, provider_order_id, amount, status,"
                + " prior_provider_status, transaction_key, opened_at, handoff_at, promise_at, next_check_at, check_attempts,"
                + " refund_requested_at, concluded_at, reason) values (?, 'test-pg', ?, 1000, ?, 'PENDING', 'TR-1',"
                + " '2026-10-21 00:02:00', '2026-10-23 00:02:00', '2026-10-25 00:02:00', ?, 2, ?, ?, ?)",
            orderId, "LP-ORD-" + orderId, status, nextCheckAt, refundRequestedAt, concludedAt, reason);
    }

    private List<String> states() {
        return jdbcTemplate.queryForList(SELECT_STATE).stream()
            .map(row -> String.join("|", String.valueOf(row.get("order_id")), String.valueOf(row.get("money_status")),
                String.valueOf(row.get("refund_actor")), String.valueOf(row.get("owner")), String.valueOf(row.get("owner_reason")),
                String.valueOf(row.get("owner_changed_at")), String.valueOf(row.get("owner_closed_at")),
                String.valueOf(row.get("owner_closed_by")), String.valueOf(row.get("next_check_at")),
                String.valueOf(row.get("refund_requested_at")), String.valueOf(row.get("concluded_at")),
                String.valueOf(row.get("check_attempts"))))
            .toList();
    }

    // 사람 담당 종료 칸은 옛 모델에 대응하는 값이 없어 모든 행에서 비어 있다. 조회 횟수는 그대로 2다.
    private static String state(long orderId, String moneyStatus, String refundActor, String owner, String ownerReason,
        String ownerChangedAt, String nextCheckAt, String refundRequestedAt, String concludedAt) {
        return String.join("|", String.valueOf(orderId), moneyStatus, String.valueOf(refundActor), owner,
            String.valueOf(ownerReason), String.valueOf(ownerChangedAt), "null", "null", String.valueOf(nextCheckAt),
            String.valueOf(refundRequestedAt), String.valueOf(concludedAt), "2");
    }

    private void run() {
        new ResourceDatabasePopulator(new ClassPathResource(SCRIPT), new ClassPathResource(SCRIPT_V3)).execute(dataSource);
    }

    private List<Map<String, Object>> columns() {
        return jdbcTemplate.queryForList("select column_name, column_type, is_nullable, column_key, extra"
            + " from information_schema.columns where table_schema = database() and table_name = ? order by column_name", TABLE);
    }

    private List<String> indexColumns(String index) {
        return jdbcTemplate.queryForList("select column_name from information_schema.statistics where table_schema = database()"
            + " and table_name = ? and index_name = ? order by seq_in_index", String.class, TABLE, index);
    }
}
