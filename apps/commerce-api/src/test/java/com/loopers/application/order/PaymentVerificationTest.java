package com.loopers.application.order;

import com.loopers.application.order.port.OrderRepository;
import com.loopers.application.payment.PaymentGatewayRegistry;
import com.loopers.application.payment.PaymentLookupPolicy;
import com.loopers.application.payment.port.PaymentAnomalyRepository;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentLookupScheduleRepository;
import com.loopers.application.payment.port.PaymentRecordRepository;
import com.loopers.application.payment.port.PaymentTransaction;
import com.loopers.application.payment.port.PaymentTransactionStatus;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.payment.LookupBackoff;
import com.loopers.domain.payment.PaymentRecord;
import com.loopers.domain.product.ProductId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PaymentVerificationTest {
    private OrderApplicationService service;
    private PaymentGateway gateway;
    private PaymentAnomalyRepository anomalies;
    private ProductRepository products;
    private PaymentRecord payment;
    private Order order;

    @BeforeEach
    void prepare() {
        OrderRepository orders = mock(OrderRepository.class);
        PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
        products = mock(ProductRepository.class);
        anomalies = mock(PaymentAnomalyRepository.class);
        gateway = mock(PaymentGateway.class);
        when(gateway.providerId()).thenReturn("fake");
        order = Order.restore(42, 1, List.of(new OrderItem(new ProductId(1), new Quantity(2), new Money(1000))),
            Order.Status.PAYMENT_PENDING);
        payment = PaymentRecord.pgStarted(42, new Money(2000), "attempt", "fake");
        when(orders.findById(42)).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate(42)).thenReturn(Optional.of(order));
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.findByOrderId(42)).thenReturn(Optional.of(payment));
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new OrderApplicationService(orders, products, payments,
            new PaymentGatewayRegistry(List.of(gateway), List.of("fake")), new TransactionTemplate(new LocalTransactions()),
            anomalies, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), mock(PaymentLookupScheduleRepository.class),
            new PaymentLookupPolicy(new LookupBackoff(Duration.ofSeconds(5), 2, Duration.ofMinutes(1)), maximum -> maximum, 12, 100));
        clearInvocations(gateway);
    }

    @Test
    @DisplayName("한 건만 확인한 보증이면 일치 거래 한 건을 반영하고 중복 이상을 판단하지 않는다")
    void acceptsSingleCoverageWithoutClaimingAllTransactions() {
        when(gateway.verifyOrder(payment.getProviderOrderId(), null)).thenReturn(new PaymentTransactionsLookup.Found(
            List.of(new PaymentTransaction("TR-1", payment.getProviderOrderId(), new Money(2000), PaymentTransactionStatus.SUCCESS)),
            PaymentTransactionsLookup.Coverage.SINGLE));

        assertThat(service.reconcilePayment(42).status()).isEqualTo("CONFIRMED");
        assertThat(payment.getTransactionKey()).isEqualTo("TR-1");
        assertThat(order.getStatus()).isEqualTo(Order.Status.CONFIRMED);
        verifyNoInteractions(products);
        verify(anomalies).resolveTransient(42, Instant.EPOCH);
        verifyNoMoreInteractions(anomalies);
    }

    @ParameterizedTest(name = "보증={0}, 이상={1}")
    @CsvSource({"SINGLE, COVERAGE_CONTRACT_VIOLATION", "ALL, DUPLICATE_TRANSACTIONS"})
    @DisplayName("거래 2건은 보증별 영구 이상 한 건을 기록하고 확인 필요·결제 대기·기존 키·재고를 유지한다")
    void quarantinesMultipleTransactionsByCoverage(PaymentTransactionsLookup.Coverage coverage,
        PaymentAnomalyRepository.Kind expectedKind) {
        when(gateway.verifyOrder(payment.getProviderOrderId(), null)).thenReturn(new PaymentTransactionsLookup.Found(
            List.of(new PaymentTransaction("TR-1", payment.getProviderOrderId(), new Money(2000), PaymentTransactionStatus.SUCCESS),
                new PaymentTransaction("TR-2", payment.getProviderOrderId(), new Money(2000), PaymentTransactionStatus.FAILED)), coverage));

        assertThat(service.reconcilePayment(42).status()).isEqualTo("PAYMENT_PENDING");

        assertThat(order.getStatus()).isEqualTo(Order.Status.PAYMENT_PENDING);
        assertThat(payment.getResult()).isEqualTo(PaymentRecord.Result.PENDING);
        assertThat(payment.getProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.NEEDS_REVIEW);
        assertThat(payment.getTransactionKey()).isNull();
        verifyNoInteractions(products);
        verify(anomalies).record(42, expectedKind, "count=2", Instant.EPOCH);
        verifyNoMoreInteractions(anomalies);
    }

    @Test
    @DisplayName("확인 필요에 결과 모름 보고가 들어오면 상태와 저장 키를 유지한다")
    void preservesReviewForUnknownReport() {
        payment.applyProviderReport(PaymentRecord.ProviderStatus.PENDING, "TR-1");
        payment.requireReview();

        service.applyPaymentResult(42, new PaymentReport(PaymentRecord.ProviderStatus.UNKNOWN, null));

        assertThat(payment.getProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.NEEDS_REVIEW);
        assertThat(payment.getTransactionKey()).isEqualTo("TR-1");
        assertThat(order.getStatus()).isEqualTo(Order.Status.PAYMENT_PENDING);
        verifyNoInteractions(anomalies, products, gateway);
    }

    private static class LocalTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
