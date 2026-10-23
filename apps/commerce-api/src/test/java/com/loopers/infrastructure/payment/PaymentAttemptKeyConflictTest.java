package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentRequestConflictException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentAttemptKeyConflictTest {
    private final PaymentAttemptJpaRepository jpa = mock(PaymentAttemptJpaRepository.class);
    private final PaymentAttemptRepositoryImpl repository = new PaymentAttemptRepositoryImpl(jpa);
    private final PaymentAttempt attempt = mock(PaymentAttempt.class);

    @ParameterizedTest
    @ValueSource(strings = {"uk_payment_attempt_key", "payment_attempt.uk_payment_attempt_key"})
    void translatesOnlyTheNamedAttemptKeyUniqueViolation(String constraint) {
        when(jpa.save(attempt)).thenThrow(violation(constraint));

        assertThatThrownBy(() -> repository.save(attempt)).isInstanceOf(PaymentRequestConflictException.class)
            .hasMessage("Payment request conflicts with an existing attempt").hasNoCause();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"uk_payment_attempt_order", "uk_payment_attempt_provider_order", "ck_fingerprint", "other_key"})
    void doesNotHideOtherDatabaseFailuresAsRequestConflicts(String constraint) {
        var failure = violation(constraint);
        when(jpa.save(attempt)).thenThrow(failure);

        assertThatThrownBy(() -> repository.save(attempt)).isSameAs(failure);
    }

    @Test
    void lockFailureIsNotARequestConflict() {
        var failure = new CannotAcquireLockException("test-only lock failure");
        when(jpa.save(attempt)).thenThrow(failure);

        assertThatThrownBy(() -> repository.save(attempt)).isSameAs(failure);
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("test-only storage failure",
            new ConstraintViolationException("test-only constraint failure", new SQLException("test-only"), constraint));
    }
}
