package com.loopers.order.application;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.Payment;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentMethod;
import com.loopers.order.domain.PaymentStatus;
import com.loopers.order.domain.PaymentGateway.ApprovalRequest;
import com.loopers.order.infrastructure.payment.toss.TossPgFixture;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.Stock;
import com.loopers.support.fixture.TestEntities;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
@DisplayName("동기 PG 결제 흐름")
class OrderPaymentFlowIntegrationTest {

    private static final String PROCESSED_ATTEMPT_KEY = "test-attempt-42";
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-10-21T15:00:00+09:00");

    @Autowired private OrderUseCase useCase;
    @Autowired private EntityManager entityManager;
    @Autowired private OrderPaymentTransactionService paymentTransactionService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private ConfigurableApplicationContext applicationContext;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private PaymentGateway paymentGateway;

    @DisplayName("[INV-ORDER-46] PG 결제가 완료되면 주문 품목의 재고 예약을 확정하고 주문 상태를 CONFIRMED로 바꾼다.")
    @Nested
    class ConfirmOrderAfterProviderApproval {

        @DisplayName("[INV-STOCK-49] PG 성공으로 예약을 확정한 뒤 가용 재고는 예약 직후와 같다. 예약 확정에 따른 추가 차감은 0이다.")
        @Nested
        class ReservationConsumptionDoesNotDeductTwice {

            @Test
            void consumesReservationAfterProviderApproval() {
                Scenario scenario = confirmationScenario(5);
                String attemptKey = newAttemptKey();
                when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));

                useCase.confirm(scenario.buyerId(), scenario.orderId(), attemptKey);
                entityManager.flush();
                entityManager.clear();
                Order confirmed = entityManager.find(Order.class, scenario.orderId());

                assertAll(
                    () -> assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                    () -> assertThat(stockReservationStatus(scenario.orderId())).isEqualTo("CONSUMED"),
                    () -> assertThat(confirmed.getPayment().status()).isEqualTo(PaymentStatus.SUCCESS),
                    () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(3),
                    () -> verify(paymentGateway).approve(any())
                );
            }
        }

        @DisplayName("[상태 전이] 최초 승인 결과가 UNKNOWN이면 승인 요청은 반복하지 않고 paymentKey 조회를 재시도해 성공 확인 후 주문을 확정한다.")
        @Test
        void confirmsOrderWhenUnknownPaymentIsFoundApproved() {
            Scenario scenario = confirmationScenario(5);
            String paymentKey = "test-payment-recovered";
            String attemptKey = newAttemptKey();
            var approved = new PaymentGateway.Approved(paymentKey,
                "LP-ORD-%010d".formatted(scenario.orderId()), 4_000L, APPROVED_AT);
            when(paymentGateway.approve(any())).thenReturn(new PaymentGateway.Unknown());
            when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(new PaymentGateway.Unknown(), approved);

            useCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);
            entityManager.flush();
            entityManager.clear();
            Order confirmed = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(confirmed.getPayment().status()).isEqualTo(PaymentStatus.SUCCESS),
                () -> assertThat(stockReservationStatus(scenario.orderId())).isEqualTo("CONSUMED"),
                () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(3),
                () -> verify(paymentGateway, times(1)).approve(any()),
                () -> verify(paymentGateway, times(2)).findByPaymentKey(paymentKey)
            );
        }
    }

    @DisplayName("앱 재기동 시 PENDING·UNKNOWN 결제를 PG 조회로 복구한다.")
    @Nested
    class RecoverPendingPaymentsOnStartup {

        @DisplayName("PENDING 결제가 PG에서 승인된 상태면 주문을 확정하고 예약을 소비한다.")
        @Test
        void confirmsPendingPaymentFoundApprovedOnStartup() {
            Scenario scenario = confirmationScenario(5);
            String paymentKey = "test-payment-startup-recovery";
            String attemptKey = newAttemptKey();
            var plan = paymentTransactionService.prepare(
                scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);
            when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(approved(plan.request()));
            entityManager.flush();
            entityManager.clear();

            eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, java.time.Duration.ZERO));
            entityManager.flush();
            entityManager.clear();
            Order recovered = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(recovered.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(recovered.getPayment().status()).isEqualTo(PaymentStatus.SUCCESS),
                () -> assertThat(stockReservationStatus(scenario.orderId())).isEqualTo("CONSUMED"),
                () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(3),
                () -> verify(paymentGateway).findByPaymentKey(paymentKey),
                () -> verify(paymentGateway, never()).approve(any())
            );
        }

        @DisplayName("UNKNOWN 결제가 PG 조회에서 승인된 상태면 주문을 확정하고 예약을 소비한다.")
        @Test
        void confirmsUnknownPaymentFoundApprovedOnStartup() {
            Scenario scenario = confirmationScenario(5);
            String paymentKey = "test-payment-startup-unknown";
            String attemptKey = newAttemptKey();
            var plan = paymentTransactionService.prepare(
                scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);
            plan.order().getPayment().recordResult(new PaymentGateway.Unknown());
            when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(approved(plan.request()));
            entityManager.flush();
            entityManager.clear();

            eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, java.time.Duration.ZERO));
            entityManager.flush();
            entityManager.clear();
            Order recovered = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(recovered.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(recovered.getPayment().status()).isEqualTo(PaymentStatus.SUCCESS),
                () -> assertThat(stockReservationStatus(scenario.orderId())).isEqualTo("CONSUMED"),
                () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(3),
                () -> verify(paymentGateway).findByPaymentKey(paymentKey),
                () -> verify(paymentGateway, never()).approve(any())
            );
        }

        @DisplayName("UNKNOWN 결제를 조회해도 최종 결과가 UNKNOWN이면 상태와 재고 예약을 유지한다.")
        @Test
        void keepsUnknownPaymentUnchangedWhenLookupRemainsUnknownOnStartup() {
            Scenario scenario = confirmationScenario(5);
            String paymentKey = "test-payment-startup-still-unknown";
            String attemptKey = newAttemptKey();
            var plan = paymentTransactionService.prepare(
                scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);
            plan.order().getPayment().recordResult(new PaymentGateway.Unknown());
            when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(new PaymentGateway.Unknown());
            entityManager.flush();
            entityManager.clear();

            eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, java.time.Duration.ZERO));
            entityManager.flush();
            entityManager.clear();
            Order unresolved = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(unresolved.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(unresolved.getPayment().status()).isEqualTo(PaymentStatus.UNKNOWN),
                () -> assertThat(stockReservationStatus(scenario.orderId())).isEqualTo("RESERVED"),
                () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(3),
                () -> verify(paymentGateway).findByPaymentKey(paymentKey),
                () -> verify(paymentGateway, never()).approve(any()),
                () -> verify(paymentGateway, never()).cancel(any())
            );
        }

        @DisplayName("TX A와 PG 승인이 끝난 직후 프로세스가 죽어도 재기동 후 PG 조회로 주문을 확정한다.")
        @Test
        void recoversApprovedPaymentAfterProcessDiesBeforeTransactionB() throws Exception {
            Scenario scenario = inTransaction(() -> confirmationScenario(5));
            String paymentKey = "test-payment-process-restart-" + UUID.randomUUID();
            String attemptKey = newAttemptKey();
            String providerOrderId = "LP-ORD-%010d".formatted(scenario.orderId());
            ApprovalRequest request = new ApprovalRequest(paymentKey, providerOrderId, 4_000L, attemptKey);

            try (TossPgFixture pg = new TossPgFixture()) {
                pg.approve(request, APPROVED_AT);
                pg.payment(paymentKey, 200, """
                    {
                      "paymentKey": "%s",
                      "orderId": "%s",
                      "totalAmount": 4000,
                      "status": "DONE",
                      "approvedAt": "%s"
                    }
                    """.formatted(paymentKey, providerOrderId, APPROVED_AT));

                try {
                    ChildProcessResult crashedProcess = runPaymentProcess(
                        "approve-and-halt", pg.baseUrl().toString(), scenario, paymentKey, attemptKey);
                    assertThat(crashedProcess.exitCode())
                        .withFailMessage("First process output:\n%s", crashedProcess.output())
                        .isEqualTo(73);
                    assertThat(readPaymentFlowState(scenario.orderId(), scenario.productId()))
                        .isEqualTo(new PaymentFlowState(OrderStatus.DRAFT, PaymentStatus.PENDING, "RESERVED", 3));

                    ChildProcessResult restartedProcess = runPaymentProcess(
                        "recover", pg.baseUrl().toString(), scenario, paymentKey, attemptKey);
                    assertThat(restartedProcess.exitCode())
                        .withFailMessage("Restarted process output:\n%s", restartedProcess.output())
                        .isZero();

                    assertAll(
                        () -> assertThat(readPaymentFlowState(scenario.orderId(), scenario.productId()))
                            .isEqualTo(new PaymentFlowState(
                                OrderStatus.CONFIRMED, PaymentStatus.SUCCESS, "CONSUMED", 3)),
                        () -> assertThat(pg.approvalRequests()).hasSize(1),
                        () -> assertThat(pg.paymentRequests(paymentKey)).hasSize(1),
                        () -> assertThat(pg.unmatchedRequests()).isEmpty()
                    );
                } finally {
                    deleteScenario(scenario);
                }
            }
        }

        @DisplayName("PENDING 결제가 PG에서 실패한 상태면 재고 예약을 반환한다.")
        @Test
        void releasesReservationWhenPendingPaymentFailedOnProvider() {
            Scenario scenario = confirmationScenario(5);
            String paymentKey = "test-payment-startup-failed";
            String attemptKey = newAttemptKey();
            paymentTransactionService.prepare(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);
            when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(new PaymentGateway.ProviderError(
                403, "REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다."));
            entityManager.flush();
            entityManager.clear();

            eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, java.time.Duration.ZERO));
            entityManager.flush();
            entityManager.clear();
            Order recovered = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(recovered.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(recovered.getPayment().status()).isEqualTo(PaymentStatus.FAILED),
                () -> assertThat(stockReservationStatus(scenario.orderId())).isNull(),
                () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(5),
                () -> verify(paymentGateway).findByPaymentKey(paymentKey),
                () -> verify(paymentGateway, never()).approve(any())
            );
        }

        @DisplayName("PG 조회가 404이면 무효 결제로 기록하고 재고 예약을 반환한다.")
        @Test
        void marksPaymentFailedAndReleasesReservationWhenLookupReturnsNotFound() {
            Scenario scenario = confirmationScenario(5);
            String paymentKey = "test-payment-startup-not-found";
            String attemptKey = newAttemptKey();
            paymentTransactionService.prepare(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);
            when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(
                new PaymentGateway.ProviderError(404, "PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));
            entityManager.flush();
            entityManager.clear();

            eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, java.time.Duration.ZERO));
            entityManager.flush();
            entityManager.clear();
            Order recovered = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(recovered.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(recovered.getPayment().status()).isEqualTo(PaymentStatus.FAILED),
                () -> assertThat(stockReservationStatus(scenario.orderId())).isNull(),
                () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(5),
                () -> verify(paymentGateway).findByPaymentKey(paymentKey),
                () -> verify(paymentGateway, never()).approve(any()),
                () -> verify(paymentGateway, never()).cancel(any())
            );
        }

    }

    @DisplayName("[INV-ORDER-48] 결제 결과 확인을 위한 재조회가 끝난 뒤 PG 취소 요청도 최대 3회 미확정이면 재고 예약은 반환 완료되고 결제 상태는 CANCEL_PROCESSING이다. 취소가 확인되면 결제 상태는 CANCELED다.")
    @Nested
    class CancelAfterUnresolvedPaymentLookups {

        @DisplayName("[상태 전이] paymentKey 조회 3회가 미확정이면 PG에 취소를 요청하고, 취소 확인 후 결제를 CANCELED로 기록하고 예약을 반환한다.")
        @Test
        void recordsConfirmedCancellationAfterThreeUnresolvedPaymentLookups() {
            Scenario scenario = confirmationScenario(5);
            String paymentKey = "test-payment-unresolved";
            String attemptKey = newAttemptKey();
            when(paymentGateway.approve(any())).thenReturn(new PaymentGateway.Unknown());
            when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(
                new PaymentGateway.Unknown(), new PaymentGateway.Unknown(), new PaymentGateway.Unknown());
            when(paymentGateway.cancel(any())).thenReturn(
                new PaymentGateway.CancellationUnknown(null),
                new PaymentGateway.CancellationUnknown(null),
                new PaymentGateway.CancellationConfirmed(paymentKey, null));

            CoreException failure = assertThrows(CoreException.class,
                () -> useCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey));
            entityManager.flush();
            entityManager.clear();
            Order order = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCELLATION_PENDING),
                () -> assertThat(order.getPayment().status()).isEqualTo(PaymentStatus.CANCELED),
                () -> assertThat(stockReservationStatus(scenario.orderId())).isNull(),
                () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(5),
                () -> verify(paymentGateway, times(1)).approve(any()),
                () -> verify(paymentGateway, times(3)).findByPaymentKey(paymentKey),
                () -> verify(paymentGateway, times(3)).cancel(argThat(request ->
                    request.paymentKey().equals(paymentKey)
                        && request.idempotencyKey().equals(attemptKey + "-cancel")))
            );
        }

        @DisplayName("[상태 전이] 취소 요청도 3회 미확정이면 같은 요청을 3회 보내고 결제는 CANCEL_PROCESSING, 재고 예약은 반환 상태다.")
        @Test
        void returnsReservationWhenThreeCancellationAttemptsRemainUnknown() {
            Scenario scenario = confirmationScenario(5);
            String paymentKey = "test-payment-cancel-unresolved";
            String attemptKey = newAttemptKey();
            when(paymentGateway.approve(any())).thenReturn(new PaymentGateway.Unknown());
            when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(
                new PaymentGateway.Unknown(), new PaymentGateway.Unknown(), new PaymentGateway.Unknown());
            when(paymentGateway.cancel(any())).thenReturn(
                new PaymentGateway.CancellationUnknown(null),
                new PaymentGateway.CancellationUnknown(null),
                new PaymentGateway.CancellationUnknown(null));

            CoreException failure = assertThrows(CoreException.class,
                () -> useCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey));
            entityManager.flush();
            entityManager.clear();
            Order cancelProcessing = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCELLATION_PENDING),
                () -> assertThat(cancelProcessing.getPayment().status()).isEqualTo(PaymentStatus.CANCEL_PROCESSING),
                () -> assertThat(stockReservationStatus(scenario.orderId())).isNull(),
                () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(5),
                () -> verify(paymentGateway, times(3)).cancel(argThat(request ->
                    request.paymentKey().equals(paymentKey)
                        && request.idempotencyKey().equals(attemptKey + "-cancel")))
            );
        }
    }

    @DisplayName("[카드 승인 거절] 주문은 DRAFT로 남고 재고 예약을 반환한다.")
    @Test
    void releasesReservationAfterCardRejection() {
        Scenario scenario = confirmationScenario(5);
        String attemptKey = newAttemptKey();
        when(paymentGateway.approve(any())).thenReturn(new PaymentGateway.ProviderError(
            403, "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));

        CoreException failure = assertThrows(CoreException.class,
            () -> useCase.confirm(scenario.buyerId(), scenario.orderId(), attemptKey));
        entityManager.flush();
        entityManager.clear();
        Order order = entityManager.find(Order.class, scenario.orderId());

        assertAll(
            () -> assertThat(failure.getErrorCode().name()).isEqualTo("PAYMENT_FAILED"),
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(order.getPayment().status()).isEqualTo(PaymentStatus.FAILED),
            () -> assertThat(stockReservationStatus(scenario.orderId())).isNull(),
            () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(5),
            () -> verify(paymentGateway).approve(any())
        );
    }

    @DisplayName("[PG Bulkhead 거절] 승인 HTTP 요청이 전송되지 않으면 결제를 실패 처리하고 재고 예약을 반환한다.")
    @Test
    void releasesReservationWhenPgApprovalIsRejectedBeforeDispatch() {
        Scenario scenario = confirmationScenario(5);
        String paymentKey = "test-payment-bulkhead-rejected";
        String attemptKey = newAttemptKey();
        when(paymentGateway.approve(any())).thenReturn(
            new PaymentGateway.DispatchRejected("PG 요청 처리 한도를 초과했습니다."));

        CoreException failure = assertThrows(CoreException.class,
            () -> useCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey));
        entityManager.flush();
        entityManager.clear();
        Order order = entityManager.find(Order.class, scenario.orderId());

        assertAll(
            () -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED),
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(order.getPayment().status()).isEqualTo(PaymentStatus.FAILED),
            () -> assertThat(stockReservationStatus(scenario.orderId())).isNull(),
            () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(5),
            () -> verify(paymentGateway).approve(any()),
            () -> verifyNoMoreInteractions(paymentGateway)
        );
    }

    @DisplayName("[조회 중 결제 실패] UNKNOWN 재조회 중 최종 실패를 확인하면 결제를 FAILED로 기록하고 재고 예약을 반환한다.")
    @Test
    void releasesReservationWhenUnknownPaymentLookupFindsFailure() {
        Scenario scenario = confirmationScenario(5);
        String paymentKey = "test-payment-retry-failed";
        String attemptKey = newAttemptKey();
        when(paymentGateway.approve(any())).thenReturn(new PaymentGateway.Unknown());
        when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(
            new PaymentGateway.Unknown(),
            new PaymentGateway.ProviderError(403, "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));

        CoreException failure = assertThrows(CoreException.class,
            () -> useCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey));
        entityManager.flush();
        entityManager.clear();
        Order order = entityManager.find(Order.class, scenario.orderId());

        assertAll(
            () -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED),
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(order.getPayment().status()).isEqualTo(PaymentStatus.FAILED),
            () -> assertThat(stockReservationStatus(scenario.orderId())).isNull(),
            () -> assertThat(TestEntities.stockQuantity(entityManager, scenario.productId())).isEqualTo(5),
            () -> verify(paymentGateway, times(1)).approve(any()),
            () -> verify(paymentGateway, times(2)).findByPaymentKey(paymentKey)
        );
    }

    @DisplayName("[INV-ORDER-45] PG 결제 요청 전에 주문 품목의 재고를 예약한다.")
    @Nested
    class ReserveStockBeforeProviderApproval {

        @DisplayName("[INV-STOCK-47] PG 요청 전에 주문 품목 수량만큼 재고가 예약되어 있고 그 수량은 가용 재고에서 제외된 상태다.")
        @Nested
        class ReservedQuantityIsExcludedFromAvailableStock {

            @Test
            @Transactional(propagation = Propagation.NOT_SUPPORTED)
            void persistsPendingPaymentBeforeSynchronousProviderCall() {
                Scenario scenario = createScenario();
                String attemptKey = newAttemptKey();
                AtomicBoolean transactionActiveAtApproval = new AtomicBoolean(true);
                AtomicReference<PaymentFlowState> stateAtApproval = new AtomicReference<>();
                when(paymentGateway.approve(any())).thenAnswer(invocation -> {
                    transactionActiveAtApproval.set(TransactionSynchronizationManager.isActualTransactionActive());
                    stateAtApproval.set(readPaymentFlowState(scenario.orderId(), scenario.productId()));
                    PaymentGateway.ApprovalRequest request = invocation.getArgument(0);
                    return new PaymentGateway.Approved(
                        request.paymentKey(), request.providerOrderId(), request.amount(), APPROVED_AT);
                });

                useCase.confirm(scenario.buyerId(), scenario.orderId(), attemptKey);

                assertAll(
                    () -> assertThat(transactionActiveAtApproval).isFalse(),
                    () -> assertThat(stateAtApproval).hasValue(
                        new PaymentFlowState(OrderStatus.DRAFT, PaymentStatus.PENDING, "RESERVED", 3)),
                    () -> verify(paymentGateway).approve(any())
                );
            }
        }
    }

    @DisplayName("[INV-PAYMENT-51] 이미 사용한 멱등키의 결제 요청을 PG로 다시 보내지 않는다.")
    @Nested
    class RejectProcessedPaymentAttempt {

        @DisplayName("[상태 전이] SUCCESS 결제에 같은 멱등키로 재요청하면 PAYMENT_ALREADY_PROCESSED이고 PG를 호출하지 않는다.")
        @Test
        void rejectsRetryForSuccessfulPayment() {
            Scenario scenario = confirmationScenario(5);
            Payment payment = new Payment(PaymentMethod.PG, 4_000L, PROCESSED_ATTEMPT_KEY,
                "LP-ORD-0000000042");
            payment.recordResult(new PaymentGateway.Approved(
                "test-payment-42", "LP-ORD-0000000042", 4_000L, APPROVED_AT));
            attachPayment(scenario.orderId(), payment);

            CoreException failure = assertThrows(CoreException.class,
                () -> useCase.confirm(scenario.buyerId(), scenario.orderId(), PROCESSED_ATTEMPT_KEY));
            entityManager.clear();
            Order order = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getPayment().status()).isEqualTo(PaymentStatus.SUCCESS),
                () -> org.mockito.Mockito.verifyNoInteractions(paymentGateway)
            );
        }

        @DisplayName("[상태 전이] FAILED 결제에 같은 멱등키로 재요청하면 PAYMENT_ALREADY_PROCESSED이고 PG를 호출하지 않는다.")
        @Test
        void rejectsRetryForFailedPayment() {
            Scenario scenario = confirmationScenario(5);
            Payment payment = new Payment(PaymentMethod.PG, 4_000L, PROCESSED_ATTEMPT_KEY,
                "LP-ORD-0000000042");
            payment.recordResult(new PaymentGateway.ProviderError(403, "REJECT_CARD_PAYMENT", "거절"));
            attachPayment(scenario.orderId(), payment);

            CoreException failure = assertThrows(CoreException.class,
                () -> useCase.confirm(scenario.buyerId(), scenario.orderId(), PROCESSED_ATTEMPT_KEY));
            entityManager.clear();
            Order order = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getPayment().status()).isEqualTo(PaymentStatus.FAILED),
                () -> org.mockito.Mockito.verifyNoInteractions(paymentGateway)
            );
        }

        @DisplayName("[상태 전이] UNKNOWN 결제에 같은 멱등키로 재요청하면 PAYMENT_ALREADY_PROCESSED이고 PG를 호출하지 않는다.")
        @Test
        void rejectsRetryForUnknownPayment() {
            Scenario scenario = confirmationScenario(5);
            Payment payment = new Payment(PaymentMethod.PG, 4_000L, PROCESSED_ATTEMPT_KEY,
                "LP-ORD-0000000042");
            payment.recordResult(new PaymentGateway.Unknown());
            attachPayment(scenario.orderId(), payment);

            CoreException failure = assertThrows(CoreException.class,
                () -> useCase.confirm(scenario.buyerId(), scenario.orderId(), PROCESSED_ATTEMPT_KEY));
            entityManager.clear();
            Order order = entityManager.find(Order.class, scenario.orderId());

            assertAll(
                () -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getPayment().status()).isEqualTo(PaymentStatus.UNKNOWN),
                () -> org.mockito.Mockito.verifyNoInteractions(paymentGateway)
            );
        }

    }

    private void attachPayment(Long orderId, Payment payment) {
        Order order = entityManager.find(Order.class, orderId);
        order.setPayment(payment);
        entityManager.flush();
        entityManager.clear();
    }

    private static String newAttemptKey() {
        return "test-attempt-flow-" + UUID.randomUUID();
    }

    private Scenario createScenario() {
        return inTransaction(() -> {
            Product product = new Product(1L, "PG 결제 상품 " + UUID.randomUUID(), 2_000L);
            entityManager.persist(product);
            entityManager.flush();
            entityManager.persist(new Stock(product.getId(), 5));
            User buyer = new User();
            entityManager.persist(buyer);
            Order order = new Order(buyer.getId(), List.of(OrderItem.of(product, 2)));
            entityManager.persist(order);
            entityManager.flush();
            return new Scenario(buyer.getId(), order.getId(), product.getId());
        });
    }

    private Scenario confirmationScenario(int initialStock) {
        Product product = new Product(1L, "PG 승인 상품 " + UUID.randomUUID(), 2_000L);
        entityManager.persist(product);
        entityManager.flush();
        entityManager.persist(new Stock(product.getId(), initialStock));
        User buyer = new User();
        entityManager.persist(buyer);
        entityManager.persist(new Point(buyer.getId(), 10_000L));
        Order order = new Order(buyer.getId(), List.of(OrderItem.of(product, 2)));
        entityManager.persist(order);
        entityManager.flush();
        return new Scenario(buyer.getId(), order.getId(), product.getId());
    }

    private PaymentGateway.Approved approved(PaymentGateway.ApprovalRequest request) {
        return new PaymentGateway.Approved(
            request.paymentKey(), request.providerOrderId(), request.amount(), APPROVED_AT);
    }

    private ChildProcessResult runPaymentProcess(
        String mode,
        String paymentGatewayUrl,
        Scenario scenario,
        String paymentKey,
        String paymentAttemptKey
    ) throws Exception {
        String classpath = System.getProperty("commerce.api.test.classpath");
        String jdbcUrl = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String username = System.getProperty("datasource.mysql-jpa.main.username");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        Path outputFile = Files.createTempFile("payment-recovery-process-", ".log");
        ProcessBuilder processBuilder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath,
            PaymentRecoveryProcessMain.class.getName(),
            mode, jdbcUrl, username, password, paymentGatewayUrl, TossPgFixture.SECRET_KEY,
            scenario.buyerId().toString(), scenario.orderId().toString(), paymentKey, paymentAttemptKey);
        processBuilder.redirectErrorStream(true).redirectOutput(outputFile.toFile());
        Process process = processBuilder.start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly().waitFor();
        }
        String output = Files.readString(outputFile);
        Files.deleteIfExists(outputFile);
        return new ChildProcessResult(exited ? process.exitValue() : -1, output);
    }

    private void deleteScenario(Scenario scenario) {
        inTransaction(() -> {
            entityManager.clear();
            Order order = entityManager.find(Order.class, scenario.orderId());
            if (order != null) {
                entityManager.remove(order);
            }
            entityManager.createQuery("select s from Stock s where s.productId = :productId", Stock.class)
                .setParameter("productId", scenario.productId())
                .getResultList()
                .forEach(entityManager::remove);
            entityManager.createQuery("select p from Point p where p.userId = :userId", Point.class)
                .setParameter("userId", scenario.buyerId())
                .getResultList()
                .forEach(entityManager::remove);
            entityManager.remove(entityManager.find(Product.class, scenario.productId()));
            entityManager.remove(entityManager.find(User.class, scenario.buyerId()));
            return null;
        });
    }

    private String stockReservationStatus(Long orderId) {
        Object status = entityManager.createQuery(
                "select o.stockReservationStatus from Order o where o.id = :orderId", Object.class)
            .setParameter("orderId", orderId)
            .getSingleResult();
        return status == null ? null : status.toString();
    }

    private PaymentFlowState readPaymentFlowState(Long orderId, Long productId) {
        return inTransaction(() -> {
            entityManager.clear();
            Order order = entityManager.find(Order.class, orderId);
            Payment payment = order.getPayment();
            return new PaymentFlowState(
                order.getStatus(),
                payment == null ? null : payment.status(),
                stockReservationStatus(orderId),
                TestEntities.stockQuantity(entityManager, productId));
        });
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> action.get());
    }

    private record Scenario(Long buyerId, Long orderId, Long productId) {
    }

    private record ChildProcessResult(int exitCode, String output) {
    }

    private record PaymentFlowState(
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        String reservationStatus,
        int stockQuantity
    ) {
    }
}
