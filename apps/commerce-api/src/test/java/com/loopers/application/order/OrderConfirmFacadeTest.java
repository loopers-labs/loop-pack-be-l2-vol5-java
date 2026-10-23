package com.loopers.application.order;

import com.loopers.application.payment.BeginConfirmationResult;
import com.loopers.application.payment.PaymentApplyResult;
import com.loopers.application.payment.PaymentConfirmCommand;
import com.loopers.application.payment.PaymentObservationApplier;
import com.loopers.application.payment.PaymentTransactionService;
import com.loopers.application.payment.PrepareConfirmationResult;
import com.loopers.domain.payment.CreateAdmission;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.fixture.PgCommands;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderConfirmFacadeTest {
    private final PaymentTransactionService tx = mock(PaymentTransactionService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentObservationApplier applier = mock(PaymentObservationApplier.class);
    private final ConstraintViolationClassifier constraints = mock(ConstraintViolationClassifier.class);
    private final OrderConfirmFacade facade = new OrderConfirmFacade(tx, gateway, applier, constraints);
    private final PaymentConfirmCommand command = PgCommands.forOrder(1L);
    private final OrderInfo pending = new OrderInfo(1L, 2L, "PAYMENT_PENDING", 1000L, 0L, null, List.of(),
        new OrderInfo.PaymentInfo("PG", "UNKNOWN", command.paymentAttemptKey(), 1000L, "NOT_CHECKED"));
    private final PrepareConfirmationResult ready = new PrepareConfirmationResult(PrepareConfirmationResult.Status.READY, null);
    private final PrepareConfirmationResult stored = new PrepareConfirmationResult(PrepareConfirmationResult.Status.STORED, pending);

    @Test
    void storedResultHasPriorityOverCircuitAdmission() {
        when(tx.prepareConfirmation(2L, 1L, command)).thenReturn(stored);
        assertThat(facade.confirm(2L, 1L, command).order()).isEqualTo(pending);
        verifyNoInteractions(gateway, applier);
    }

    @Test
    void blockedAdmissionRechecksAndReturnsConcurrentStoredAttempt() {
        when(tx.prepareConfirmation(2L, 1L, command)).thenReturn(ready, stored);
        when(gateway.checkCreateAdmission()).thenReturn(CreateAdmission.BLOCKED);
        assertThat(facade.confirm(2L, 1L, command).order()).isEqualTo(pending);
        verify(tx, never()).beginConfirmation(2L, 1L, command);
        verifyNoInteractions(applier);
    }

    @Test
    void blockedAdmissionDoesNotBeginOrSend() {
        when(tx.prepareConfirmation(2L, 1L, command)).thenReturn(ready);
        when(gateway.checkCreateAdmission()).thenReturn(CreateAdmission.BLOCKED);
        assertThatThrownBy(() -> facade.confirm(2L, 1L, command))
            .hasFieldOrPropertyWithValue("errorType", ErrorType.PG_CREATE_UNAVAILABLE);
        verify(tx, never()).beginConfirmation(2L, 1L, command);
        verifyNoInteractions(applier);
    }

    @Test
    void beginStoredResultDoesNotSend() {
        when(tx.prepareConfirmation(2L, 1L, command)).thenReturn(ready);
        when(gateway.checkCreateAdmission()).thenReturn(CreateAdmission.READY);
        when(tx.beginConfirmation(2L, 1L, command)).thenReturn(
            new BeginConfirmationResult(BeginConfirmationResult.Status.STORED, null, null, pending));
        assertThat(facade.confirm(2L, 1L, command).order()).isEqualTo(pending);
        verifyNoInteractions(applier);
        verify(gateway, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onlyNamedBeginConstraintIsRecoveredInFreshPreparation() {
        var failure = new DataIntegrityViolationException("unique");
        when(tx.prepareConfirmation(2L, 1L, command)).thenReturn(ready, stored);
        when(gateway.checkCreateAdmission()).thenReturn(CreateAdmission.READY);
        when(tx.beginConfirmation(2L, 1L, command)).thenThrow(failure);
        when(constraints.namedUniqueViolation(failure)).thenReturn(Optional.of("uk_payment_key"));
        assertThat(facade.confirm(2L, 1L, command).order()).isEqualTo(pending);
        verifyNoInteractions(applier);
    }

    @Test
    void unknownConstraintPropagatesWithoutRecoveryOrPgObservation() {
        var failure = new DataIntegrityViolationException("unknown");
        when(tx.prepareConfirmation(2L, 1L, command)).thenReturn(ready);
        when(gateway.checkCreateAdmission()).thenReturn(CreateAdmission.READY);
        when(tx.beginConfirmation(2L, 1L, command)).thenThrow(failure);
        when(constraints.namedUniqueViolation(failure)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> facade.confirm(2L, 1L, command)).isSameAs(failure);
        verifyNoInteractions(applier);
    }

    @Test
    void gatewayProgrammingErrorDoesNotBecomeUnavailableOrInvokeTxB() {
        PaymentRequest request = new PaymentRequest("LP-ORD-0000000001", 1000, "TEST", "0000-0000-0000-0000");
        PaymentCheckToken token = new PaymentCheckToken(3L, 1L, ObservationSource.CREATE, false);
        when(tx.prepareConfirmation(2L, 1L, command)).thenReturn(ready);
        when(gateway.checkCreateAdmission()).thenReturn(CreateAdmission.READY);
        when(tx.beginConfirmation(2L, 1L, command)).thenReturn(
            new BeginConfirmationResult(BeginConfirmationResult.Status.SEND, request, token, null));
        var failure = new IllegalStateException("program error");
        when(gateway.create(request)).thenThrow(failure);
        assertThatThrownBy(() -> facade.confirm(2L, 1L, command)).isSameAs(failure);
        verifyNoInteractions(applier);
    }

    @Test
    void callOrderIsPrepareAdmissionBeginCreateThenApplyWithoutAdditionalLookup() {
        PaymentRequest request = new PaymentRequest("LP-ORD-0000000001", 1000, "TEST", "0000-0000-0000-0000");
        PaymentCheckToken token = new PaymentCheckToken(3L, 1L, ObservationSource.CREATE, false);
        PaymentObservation observation = new PaymentObservation.Unavailable(ObservationSource.CREATE);
        PaymentApplyResult expected = new PaymentApplyResult(pending, PaymentApplyResult.Outcome.UNAVAILABLE);
        when(tx.prepareConfirmation(2L, 1L, command)).thenReturn(ready);
        when(gateway.checkCreateAdmission()).thenReturn(CreateAdmission.READY);
        when(tx.beginConfirmation(2L, 1L, command)).thenReturn(
            new BeginConfirmationResult(BeginConfirmationResult.Status.SEND, request, token, null));
        when(gateway.create(request)).thenReturn(observation);
        when(applier.apply(token, observation)).thenReturn(expected);
        assertThat(facade.confirm(2L, 1L, command)).isEqualTo(expected);
        var order = inOrder(tx, gateway, applier);
        order.verify(tx).prepareConfirmation(2L, 1L, command);
        order.verify(gateway).checkCreateAdmission();
        order.verify(tx).beginConfirmation(2L, 1L, command);
        order.verify(gateway).create(request);
        order.verify(applier).apply(token, observation);
        order.verifyNoMoreInteractions();
    }
}
