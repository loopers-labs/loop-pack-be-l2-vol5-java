package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentAttemptPersistenceTest {

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void storesAttemptWithoutTransactionKeyAndKeepsStatusChanges() {
        PaymentAttempt attempt = paymentAttemptRepository.save(
            PaymentAttempt.start(42L, "attempt-abc", 3_000L, "TEST", "0000-0000-0000-0000")
        );
        attempt.markPending("TEST-TX-1");
        paymentAttemptRepository.save(attempt);

        PaymentAttempt stored = paymentAttemptRepository.findByOrderId(42L).orElseThrow();

        assertThat(stored.getAttemptKey()).isEqualTo("attempt-abc");
        assertThat(stored.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(stored.getAmount()).isEqualTo(3_000L);
        assertThat(stored.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(stored.getTransactionKey()).isEqualTo("TEST-TX-1");
        assertThat(stored.matchesRequest(3_000L, "TEST", "0000-0000-0000-0000")).isTrue();
        assertThat(stored.matchesRequest(3_000L, "TEST", "1111-1111-1111-1111")).isFalse();
        assertThat(stored.matchesRequest(3_000L, "OTHER", "0000-0000-0000-0000")).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT request_fingerprint FROM payment_attempt WHERE id = ?", String.class, stored.getId()
        )).matches("[0-9a-f]{64}");
    }

    @Test
    void secondAttemptForSameOrderIsRejectedByDatabase() {
        paymentAttemptRepository.save(PaymentAttempt.start(42L, "attempt-abc", 3_000L, "TEST", "0000-0000-0000-0000"));

        assertThatThrownBy(() -> paymentAttemptRepository.save(
            PaymentAttempt.start(42L, "attempt-other", 3_000L, "TEST", "0000-0000-0000-0000")
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
    }

    @Test
    void sameAttemptKeyForAnotherOrderIsRejectedByDatabase() {
        paymentAttemptRepository.save(PaymentAttempt.start(42L, "attempt-abc", 3_000L, "TEST", "0000-0000-0000-0000"));

        assertThatThrownBy(() -> paymentAttemptRepository.save(
            PaymentAttempt.start(43L, "attempt-abc", 5_000L, "TEST", "0000-0000-0000-0000")
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
    }
}
