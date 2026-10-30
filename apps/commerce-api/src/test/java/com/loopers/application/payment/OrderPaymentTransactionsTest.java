package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loopers.application.user.IdentifyUser;
import com.loopers.config.FixtureMonkeyConfig;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.StockReservationRepository;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.navercorp.fixturemonkey.ArbitraryBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;
import java.time.Clock;

class OrderPaymentTransactionsTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderPaymentTransactions paymentTransactions = new OrderPaymentTransactions(
            mock(IdentifyUser.class), orders, mock(ProductRepository.class),
            payments, mock(StockReservationRepository.class), Clock.systemUTC(), mock(ApplicationEventPublisher.class));

    private Payment storedPayment;
    private ArbitraryBuilder<PaymentGateway.CreatedPayment> acceptanceBuilder;

    @BeforeEach
    void setUp() {
        storedPayment = Payment.start(42, UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"), 7_000);
        acceptanceBuilder = FixtureMonkeyConfig.create(42L)
                .giveMeBuilder(PaymentGateway.CreatedPayment.class)
                .set("transactionKey", "TEST-TX-1")
                .set("providerOrderId", storedPayment.providerOrderId())
                .set("amount", storedPayment.amount());
        when(orders.findByIdForUpdate(storedPayment.orderId())).thenReturn(Optional.of(mock(Order.class)));
        when(payments.findByOrderIdForUpdate(storedPayment.orderId())).thenReturn(Optional.of(storedPayment));
    }

    @Test
    void PG_결제_접수_결과의_주문번호가_저장된_주문번호와_다르면_충돌_오류로_저장을_거절한다() {
        // arrange
        var mismatchedAcceptance = acceptanceBuilder
                .set("providerOrderId", storedPayment.providerOrderId() + "-OTHER")
                .sample();

        // act
        CoreException validationFailure = assertThrows(CoreException.class, () ->
                paymentTransactions.recordAcceptance(storedPayment.orderId(), mismatchedAcceptance));

        // assert
        assertThat(validationFailure.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        verify(payments, never()).save(any(Payment.class));
    }

    @Test
    void PG_결제_접수_결과의_금액이_저장된_결제_금액과_다르면_충돌_오류로_저장을_거절한다() {
        // arrange
        var mismatchedAcceptance = acceptanceBuilder
                .set("amount", storedPayment.amount() + 1)
                .sample();

        // act
        CoreException validationFailure = assertThrows(CoreException.class, () ->
                paymentTransactions.recordAcceptance(storedPayment.orderId(), mismatchedAcceptance));

        // assert
        assertThat(validationFailure.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        verify(payments, never()).save(any(Payment.class));
    }
}
