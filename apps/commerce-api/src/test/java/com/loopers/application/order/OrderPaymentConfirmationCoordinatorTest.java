package com.loopers.application.order;

import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentLookupNotPermittedException;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentResultConflictException;
import com.loopers.domain.payment.PaymentTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Coordinator sequencing only; real DB reservation/lock and actual HTTP evidence live in integration tests. */
class OrderPaymentConfirmationCoordinatorTest {
    private static final long ORDER_ID = 42L;
    private static final UUID KEY = UUID.fromString("12345678-abcd-4abc-8abc-123456789abc");
    private static final String PROVIDER_ORDER = "LP-ORD-0000000042";
    private static final String CARD = "0000-0000-0000-0000";
    private final OrderPaymentStockService stocks = mock(OrderPaymentStockService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final FakeTiming timing = new FakeTiming();
    private final PaymentConfirmationDeadline deadline = PaymentConfirmationDeadline.start(timing);
    private final OrderPaymentCoordinator coordinator = new OrderPaymentCoordinator(stocks, gateway,
        new PaymentConfirmationWaiter(timing));
    private final AtomicReference<OrderPaymentStockService.AppliedPayment> state = new AtomicReference<>(result(PaymentAttemptStatus.NOT_OBSERVED));
    private final AtomicReference<String> transactionKey = new AtomicReference<>();
    private final OrderPaymentStockService.PreparedPayment prepared = new OrderPaymentStockService.PreparedPayment(
        ORDER_ID, KEY, "135135", PROVIDER_ORDER, 7000L);

    @BeforeEach
    void setup() {
        when(stocks.prepareCustomer("alice", ORDER_ID, KEY, "135135", "TEST", CARD))
            .thenReturn(new OrderPaymentStockService.PaymentPreparation(prepared, state.get()));
        when(stocks.inspectPrepared("alice", ORDER_ID, KEY)).thenAnswer(invocation -> snapshot());
        when(stocks.markUnknown(ORDER_ID, KEY)).thenAnswer(invocation -> {
            state.set(result(PaymentAttemptStatus.UNKNOWN));
            return state.get();
        });
        when(stocks.apply(eq(ORDER_ID), eq(KEY), any())).thenAnswer(invocation -> {
            PaymentTransaction observation = invocation.getArgument(2);
            transactionKey.set(observation.transactionKey());
            state.set(result(PaymentAttemptStatus.valueOf(observation.status().name())));
            return state.get();
        });
        when(gateway.create(any())).thenReturn(observed(PaymentTransaction.Status.PENDING));
        when(gateway.findByProviderOrderId(PROVIDER_ORDER)).thenReturn(PaymentGatewayResult.notFound());
        when(gateway.findByTransactionKey("TX-42")).thenReturn(PaymentGatewayResult.notFound());
    }

    @AfterEach
    void clearThreadState() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
    }

    @Test
    void committedPreparationTakingMoreThan110SecondsCannotStartCreateAndKeepsUnknown() {
        when(stocks.prepareCustomer("alice", ORDER_ID, KEY, "135135", "TEST", CARD)).thenAnswer(invocation -> {
            timing.advance(Duration.ofSeconds(111));
            return new OrderPaymentStockService.PaymentPreparation(prepared, state.get());
        });

        assertThat(start().paymentStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);

        verify(gateway, never()).create(any());
        verify(stocks, times(1)).prepareCustomer("alice", ORDER_ID, KEY, "135135", "TEST", CARD);
        verify(stocks, never()).rejectBeforeCreate(anyLong(), any(), anyString(), anyString(), anyLong());
        verify(stocks, never()).apply(anyLong(), any(), any());
        assertThat(timing.nanoTime()).isLessThanOrEqualTo(Duration.ofSeconds(175).toNanos());
        assertThat(timing.sleeps).isNotEmpty();
    }

    @Test
    void preparationThatExhaustsLookupBudgetAlsoMakesNoPgCall() {
        when(stocks.prepareCustomer("alice", ORDER_ID, KEY, "135135", "TEST", CARD)).thenAnswer(invocation -> {
            timing.advance(Duration.ofSeconds(166));
            return new OrderPaymentStockService.PaymentPreparation(prepared, state.get());
        });

        assertThat(start().paymentStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);

        verify(stocks, times(1)).markUnknown(ORDER_ID, KEY);
        verifyNoInteractions(gateway);
        assertThat(timing.sleeps).isEmpty();
    }

    @Test
    void countsRequestEntryAndPreparationTogetherWithoutResettingTheDeadline() {
        timing.advance(Duration.ofSeconds(80));
        when(stocks.prepareCustomer("alice", ORDER_ID, KEY, "135135", "TEST", CARD)).thenAnswer(invocation -> {
            timing.advance(Duration.ofSeconds(40));
            return new OrderPaymentStockService.PaymentPreparation(prepared, state.get());
        });
        when(gateway.findByProviderOrderId(PROVIDER_ORDER)).thenReturn(observed(PaymentTransaction.Status.SUCCESS));

        assertThat(start().paymentStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);

        verify(gateway, never()).create(any());
        verify(gateway, times(1)).findByProviderOrderId(PROVIDER_ORDER);
        assertThat(timing.nanoTime()).isEqualTo(Duration.ofSeconds(120).toNanos());
    }

    @Test
    void rechecksTheDeadlineAfterStoredLookupBeforeMakingTheActualGet() {
        AtomicInteger reads = new AtomicInteger();
        when(stocks.inspectPrepared("alice", ORDER_ID, KEY)).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 2) {
                timing.advance(Duration.ofSeconds(166));
            }
            return snapshot();
        });

        assertThat(start().paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);

        verify(gateway, times(1)).create(any());
        verify(gateway, never()).findByTransactionKey(anyString());
        verify(gateway, never()).findByProviderOrderId(anyString());
        verify(stocks, never()).markUnknown(ORDER_ID, KEY);
        assertThat(timing.sleeps).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void aDuplicateReturnsStoredStateWithoutCreateLookupOrWaiting(PaymentAttemptStatus status) {
        var existing = result(status);
        when(stocks.prepareCustomer("alice", ORDER_ID, KEY, "135135", "TEST", CARD))
            .thenReturn(new OrderPaymentStockService.PaymentPreparation(null, existing));

        assertThat(start()).isSameAs(existing);

        verify(stocks, times(1)).prepareCustomer("alice", ORDER_ID, KEY, "135135", "TEST", CARD);
        verifyNoMoreInteractions(stocks);
        verifyNoInteractions(gateway);
        assertThat(timing.sleeps).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anExternalResultConflictIsNotConvertedToPendingOrRequestConflict(boolean duringCreate) {
        var conflict = PaymentGatewayResult.conflict(List.of(transaction(PaymentTransaction.Status.SUCCESS)));
        when(stocks.preserveConflict(ORDER_ID, KEY)).thenReturn(new OrderPaymentStockService.AppliedPayment(
            OrderStatus.PAYMENT_PENDING, PaymentAttemptStatus.UNKNOWN, PaymentObservationResult.CONFLICT));
        if (duringCreate) {
            when(gateway.create(any())).thenReturn(conflict);
        } else {
            when(gateway.findByTransactionKey("TX-42")).thenReturn(conflict);
        }

        assertThatThrownBy(this::start).isExactlyInstanceOf(PaymentResultConflictException.class);

        verify(stocks, times(1)).preserveConflict(ORDER_ID, KEY);
        verify(stocks, never()).rejectBeforeCreate(anyLong(), any(), anyString(), anyString(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resultApplicationFailureIsPropagatedRatherThanHiddenAsAccepted(boolean duringCreate) {
        RuntimeException databaseFailure = new IllegalStateException("synthetic TX B failure");
        if (duringCreate) {
            doThrow(databaseFailure).when(stocks).apply(eq(ORDER_ID), eq(KEY), any());
        } else {
            when(gateway.findByTransactionKey("TX-42")).thenReturn(observed(PaymentTransaction.Status.SUCCESS));
            doThrow(databaseFailure).when(stocks).apply(ORDER_ID, KEY, transaction(PaymentTransaction.Status.SUCCESS));
        }

        assertThatThrownBy(this::start).isSameAs(databaseFailure);

        verify(gateway, times(1)).create(any());
        assertThat(timing.sleeps).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsAnOuterTransactionOrSynchronizationBeforePreparation(boolean activeTransaction) {
        if (activeTransaction) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
        } else {
            TransactionSynchronizationManager.initSynchronization();
        }

        assertThatThrownBy(this::start).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(stocks, gateway);
        assertThat(timing.sleeps).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void singleRefreshCircuitDenialReturnsFreshStoredStateWithoutPretendingPgFailed(boolean hasTransactionKey) {
        state.set(result(PaymentAttemptStatus.UNKNOWN));
        transactionKey.set(hasTransactionKey ? "TX-42" : null);
        var before = snapshot();
        var success = result(PaymentAttemptStatus.SUCCESS);
        var completed = new OrderPaymentStockService.StoredPayment("135135", PROVIDER_ORDER, "TX-42", success);
        when(stocks.inspectPrepared("alice", ORDER_ID, KEY)).thenReturn(before, completed);
        if (hasTransactionKey) {
            when(gateway.findByTransactionKey("TX-42")).thenThrow(new PaymentLookupNotPermittedException());
        } else {
            when(gateway.findByProviderOrderId(PROVIDER_ORDER)).thenThrow(new PaymentLookupNotPermittedException());
        }

        assertThat(coordinator.refreshOnce("alice", ORDER_ID, KEY)).isSameAs(success);

        verify(stocks, times(2)).inspectPrepared("alice", ORDER_ID, KEY);
        verify(stocks, never()).markUnknown(ORDER_ID, KEY);
        verify(stocks, never()).apply(anyLong(), any(), any());
        verify(gateway, never()).create(any());
        assertThat(timing.sleeps).isEmpty();
    }

    private OrderPaymentStockService.AppliedPayment start() {
        return coordinator.startAndAwait("alice", ORDER_ID, KEY, "TEST", CARD, deadline);
    }

    private OrderPaymentStockService.StoredPayment snapshot() {
        return new OrderPaymentStockService.StoredPayment("135135", PROVIDER_ORDER, transactionKey.get(), state.get());
    }

    private static OrderPaymentStockService.AppliedPayment result(PaymentAttemptStatus status) {
        OrderStatus orderStatus = switch (status) {
            case SUCCESS -> OrderStatus.CONFIRMED;
            case FAILED -> OrderStatus.PAYMENT_FAILED;
            default -> OrderStatus.PAYMENT_PENDING;
        };
        return new OrderPaymentStockService.AppliedPayment(orderStatus, status, PaymentObservationResult.UNCHANGED);
    }

    private static PaymentGatewayResult observed(PaymentTransaction.Status status) {
        return PaymentGatewayResult.observed(List.of(transaction(status)));
    }

    private static PaymentTransaction transaction(PaymentTransaction.Status status) {
        return new PaymentTransaction("135135", PROVIDER_ORDER, "TX-42", 7000L, status);
    }

    private static final class FakeTiming implements PaymentConfirmationTiming {
        private long now;
        private final List<Duration> sleeps = new ArrayList<>();

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
            advance(duration);
        }

        @Override
        public double jitterFactor() {
            return 1.0;
        }

        void advance(Duration duration) {
            now += duration.toNanos();
        }
    }
}
