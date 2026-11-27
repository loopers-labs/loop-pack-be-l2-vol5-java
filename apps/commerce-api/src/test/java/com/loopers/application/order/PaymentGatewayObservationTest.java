package com.loopers.application.order;

import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentLookupNotPermittedException;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class PaymentGatewayObservationTest {
    private static final long ORDER_ID = 42;
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final String PROVIDER_ORDER = "LP-ORD-0000000042";
    private static final String TRANSACTION_KEY = "TEST-TX-42";
    private static final String CUSTOMER = "private-customer-id";
    private static final String CARD = "0000-0000-0000-0000";
    private final OrderPaymentStockService stocks = mock(OrderPaymentStockService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final OrderPaymentCoordinator coordinator = new OrderPaymentCoordinator(stocks, gateway);

    @ParameterizedTest
    @EnumSource(value = PaymentGatewayResult.Failure.class, names = {"NETWORK", "TIMEOUT"})
    void unknownCreateIdentifiesOrderAttemptAndActualTransportFailure(PaymentGatewayResult.Failure failure,
                                                                     CapturedOutput output) {
        prepareCreate();
        when(gateway.create(any())).thenReturn(PaymentGatewayResult.unknown(failure));

        coordinator.startNew(CUSTOMER, ORDER_ID, ATTEMPT, PROVIDER_ORDER, "TEST", CARD);

        assertThat(output.getAll()).contains("payment_gateway_result source=CONFIRM operation=CREATE",
                "orderId=42 attemptKey=" + ATTEMPT + " providerOrderId=" + PROVIDER_ORDER,
                "transactionKey=NONE result=UNKNOWN reason=" + failure + " pgStatus=NONE", "elapsedMs=")
            .doesNotContain(CARD, CUSTOMER);
    }

    @Test
    void successfulPgLookupIsVisibleEvenIfInternalApplicationThenFails(CapturedOutput output) {
        prepareLookup(null);
        var transaction = new PaymentTransaction("135135", PROVIDER_ORDER, TRANSACTION_KEY, 200,
            PaymentTransaction.Status.SUCCESS);
        when(gateway.findByProviderOrderId(PROVIDER_ORDER)).thenReturn(PaymentGatewayResult.observed(List.of(transaction)));
        when(stocks.apply(ORDER_ID, ATTEMPT, transaction)).thenThrow(new IllegalStateException("internal test failure"));

        assertThatThrownBy(() -> coordinator.refreshOnce(CUSTOMER, ORDER_ID, ATTEMPT)).isInstanceOf(IllegalStateException.class);

        assertThat(output.getAll()).contains("payment_gateway_result source=REFRESH operation=LOOKUP_BY_ORDER",
                "orderId=42 attemptKey=" + ATTEMPT + " providerOrderId=" + PROVIDER_ORDER,
                "transactionKey=" + TRANSACTION_KEY + " result=OBSERVED reason=NONE pgStatus=SUCCESS")
            .doesNotContain("internal test failure", CUSTOMER);
    }

    @Test
    void circuitDenialLogsNoExternalObservationAndRedactsUntrustedIdentifier(CapturedOutput output) {
        String untrustedKey = CARD + "\nprivate-text";
        prepareLookup(untrustedKey);
        when(gateway.findByTransactionKey(untrustedKey)).thenThrow(new PaymentLookupNotPermittedException());

        coordinator.refreshOnce(CUSTOMER, ORDER_ID, ATTEMPT);

        assertThat(output.getAll()).contains("operation=LOOKUP_BY_KEY", "transactionKey=REDACTED",
                "result=NOT_SENT reason=CIRCUIT_OPEN pgStatus=NONE")
            .doesNotContain(CARD, "private-text", CUSTOMER);
    }

    private void prepareCreate() {
        when(stocks.prepareNew(CUSTOMER, ORDER_ID, ATTEMPT, "135135", PROVIDER_ORDER, "TEST", CARD))
            .thenReturn(new OrderPaymentStockService.PreparedPayment(ORDER_ID, ATTEMPT, "135135", PROVIDER_ORDER, 200));
    }

    private void prepareLookup(String transactionKey) {
        var pending = new OrderPaymentStockService.AppliedPayment(OrderStatus.PAYMENT_PENDING,
            PaymentAttemptStatus.UNKNOWN, PaymentObservationResult.UNCHANGED);
        when(stocks.inspectPrepared(CUSTOMER, ORDER_ID, ATTEMPT))
            .thenReturn(new OrderPaymentStockService.StoredPayment("135135", PROVIDER_ORDER, transactionKey, pending));
    }
}
