package com.loopers.infrastructure.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.PaymentDiscrepancyRepository;
import com.loopers.domain.payment.TransactionKey;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentDiscrepancyRepositoryIntegrationTest {

    private static final Long PAYMENT = 7L;
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final TransactionKey KEY = new TransactionKey("TEST-TX-0001");

    private final PaymentDiscrepancyRepository paymentDiscrepancyRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    PaymentDiscrepancyRepositoryIntegrationTest(
        PaymentDiscrepancyRepository paymentDiscrepancyRepository,
        TransactionTemplate transactionTemplate,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentDiscrepancyRepository = paymentDiscrepancyRepository;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void record(PaymentDiscrepancy discrepancy) {
        transactionTemplate.executeWithoutResult(status ->
            paymentDiscrepancyRepository.record(PAYMENT, discrepancy, PaymentDiscrepancy.Source.LOOKUP, NOW));
    }

    private List<Map<String, Object>> rows() {
        return jdbcTemplate.queryForList("""
            select reason, source, observed_transaction_key, observed_status, observed_amount, observed_at
            from payment_discrepancy where payment_id = ? order by id
            """, PAYMENT);
    }

    @DisplayName("PAY-017: 불일치를 이유, 출처, PG 가 말한 키 · 상태 · 금액과 함께 남긴다")
    @Test
    void records() {
        record(new PaymentDiscrepancy(
            PaymentDiscrepancy.Reason.AMOUNT_MISMATCH, KEY, PgTransaction.Status.SUCCESS, Money.of(9_000)));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.get("reason")).isEqualTo("AMOUNT_MISMATCH");
            assertThat(row.get("source")).isEqualTo("LOOKUP");
            assertThat(row.get("observed_transaction_key")).isEqualTo("TEST-TX-0001");
            assertThat(row.get("observed_status")).isEqualTo("SUCCESS");
            assertThat(((Number) row.get("observed_amount")).longValue()).isEqualTo(9_000);
            assertThat(row.get("observed_at")).isNotNull();
        });
    }

    @DisplayName("PAY-017: 같은 관찰이 반복돼도 한 줄만 남는다. 같은 모순 callback 10 회")
    @Test
    void recordsSameObservationOnce() {
        PaymentDiscrepancy same = new PaymentDiscrepancy(
            PaymentDiscrepancy.Reason.RESULT_AFTER_SETTLED, KEY, PgTransaction.Status.FAILED, Money.of(7_000));

        for (int i = 0; i < 10; i++) {
            record(same);
        }

        assertThat(rows()).hasSize(1);
    }

    @DisplayName("관찰한 상태가 다르면 다른 줄이다")
    @Test
    void recordsDifferentObservations() {
        record(new PaymentDiscrepancy(
            PaymentDiscrepancy.Reason.RESULT_AFTER_SETTLED, KEY, PgTransaction.Status.FAILED, Money.of(7_000)));
        record(new PaymentDiscrepancy(
            PaymentDiscrepancy.Reason.RESULT_AFTER_SETTLED, KEY, PgTransaction.Status.PENDING, Money.of(7_000)));

        assertThat(rows()).hasSize(2);
    }

    @DisplayName("관찰한 키가 없으면 빈 문자열로 남겨, 키 없는 관찰이 반복돼도 한 줄이다")
    @Test
    void recordsKeylessObservationOnce() {
        PaymentDiscrepancy keyless = new PaymentDiscrepancy(
            PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS, null, null, null);

        record(keyless);
        record(keyless);

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.get("observed_transaction_key")).isEqualTo("");
            assertThat(row.get("observed_status")).isEqualTo("");
            assertThat(row.get("observed_amount")).isNull();
        });
    }

    @DisplayName("결제 · 이유 · 관찰한 키 · 관찰한 상태에 유일 인덱스가 있다")
    @Test
    void hasUniqueIndex() {
        List<String> columns = jdbcTemplate.queryForList("""
            select column_name from information_schema.statistics
            where table_schema = database() and table_name = 'payment_discrepancy'
              and non_unique = 0 and index_name <> 'PRIMARY'
            order by seq_in_index
            """, String.class).stream().map(String::toLowerCase).toList();

        assertThat(columns).containsExactly(
            "payment_id", "reason", "observed_transaction_key", "observed_status");
    }
}
