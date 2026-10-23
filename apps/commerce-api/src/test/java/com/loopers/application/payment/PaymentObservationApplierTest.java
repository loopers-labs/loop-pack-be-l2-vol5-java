package com.loopers.application.payment;

import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PaymentObservationApplierTest {
    private final PaymentTransactionService tx = mock(PaymentTransactionService.class);
    private final ConstraintViolationClassifier constraints = mock(ConstraintViolationClassifier.class);
    private final PaymentObservationApplier applier = new PaymentObservationApplier(tx, constraints);
    private final PaymentCheckToken token = new PaymentCheckToken(1L, 2L, ObservationSource.QUERY, false);
    private final PaymentObservation.TransactionObserved observed = new PaymentObservation.TransactionObserved(
        ObservationSource.QUERY, "tx-key", "LP-ORD-0000000002", 1000L, PgTransactionStatus.SUCCESS);

    @Test
    void committedConflictIsMappedOnlyAfterTransactionReturns() {
        when(tx.applyObservation(token, observed)).thenReturn(
            new PaymentApplyResult(null, PaymentApplyResult.Outcome.CONFLICT));
        assertThatThrownBy(() -> applier.apply(token, observed))
            .hasFieldOrPropertyWithValue("errorType", ErrorType.PAYMENT_RESULT_CONFLICT);
    }

    @Test
    void transactionUniqueRollbackIsFollowedBySeparateConflictCommitThen409() {
        var failure = new DataIntegrityViolationException("transaction unique");
        when(tx.applyObservation(token, observed)).thenThrow(failure);
        when(constraints.namedUniqueViolation(failure)).thenReturn(Optional.of("uk_payment_transaction"));
        when(tx.recordConstraintConflict(token, observed)).thenReturn(
            new PaymentApplyResult(null, PaymentApplyResult.Outcome.CONFLICT));
        assertThatThrownBy(() -> applier.apply(token, observed))
            .hasFieldOrPropertyWithValue("errorType", ErrorType.PAYMENT_RESULT_CONFLICT);
        var order = inOrder(tx);
        order.verify(tx).applyObservation(token, observed);
        order.verify(tx).recordConstraintConflict(token, observed);
    }

    @Test
    void unknownConstraintDoesNotBecomeBusinessConflict() {
        var failure = new DataIntegrityViolationException("unknown");
        when(tx.applyObservation(token, observed)).thenThrow(failure);
        when(constraints.namedUniqueViolation(failure)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> applier.apply(token, observed)).isSameAs(failure);
        verify(tx).applyObservation(token, observed);
        verifyNoMoreInteractions(tx);
    }

    @Test
    void failureOfConflictRecordingPropagatesWithoutAnotherObservation() {
        var failure = new DataIntegrityViolationException("transaction unique");
        var recordFailure = new IllegalStateException("record failed");
        when(tx.applyObservation(token, observed)).thenThrow(failure);
        when(constraints.namedUniqueViolation(failure)).thenReturn(Optional.of("uk_payment_transaction"));
        when(tx.recordConstraintConflict(token, observed)).thenThrow(recordFailure);
        assertThatThrownBy(() -> applier.apply(token, observed)).isSameAs(recordFailure);
    }
}
