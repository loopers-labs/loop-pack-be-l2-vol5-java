package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loopers.application.ordering.command.PrepareOrderPaymentCommand;
import com.loopers.application.ordering.command.ReconcileOrderPaymentCommand;
import com.loopers.application.ordering.command.HandlePaymentCallbackCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.usecase.PrepareOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.ReconcileOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.HandlePaymentCallbackUseCase;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class ReconcileOrderPaymentIntegrationTest {
    @Autowired private ReconcileOrderPaymentUseCase reconcile;
    @Autowired private PrepareOrderPaymentUseCase prepare;
    @Autowired private RecordPaymentObservationUseCase observations;
    @Autowired private PaymentProviderGateway gateway;
    @Autowired private OrderRepository orders;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private PaymentRecoveryTaskRepository tasks;
    @Autowired private TransactionTemplate tx;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private CircuitBreakerRegistry circuits;
    @Autowired private HandlePaymentCallbackUseCase callback;

    @BeforeEach void resetCircuit() { circuits.circuitBreaker("pg-lookup").reset(); }
    @AfterEach void clean() { cleanup.truncateAllTables(); circuits.circuitBreaker("pg-lookup").reset(); }

    @Test
    @DisplayName("수동 대사와 callback은 공유 OPEN 회로에서 PG 미호출·차감 유지·자동 회차 보존이다")
    void sharesOpenCircuitWithCallback() {
        PaymentAttempt attempt = prepared();
        circuits.circuitBreaker("pg-lookup").transitionToOpenState();
        ConfirmOrderResult result = reconcile.execute(command(attempt));
        assertThat(result.completed()).isFalse();
        assertThat(result.attentionRequired()).isTrue();
        assertThat(result.paymentAttempt().lastReason()).isEqualTo(PaymentRecoveryTask.Reason.CIRCUIT_BLOCKED);
        assertThat(result.paymentAttempt().usedRounds()).isZero();
        assertThat(result.paymentAttempt().outcome()).isEqualTo(PaymentAttempt.Outcome.UNKNOWN);
        assertThat(callback.execute(new HandlePaymentCallbackCommand(attempt.getProviderOrderId(), "PG-1", ProviderPaymentStatus.SUCCESS)).completed()).isFalse();
        assertThat(stock(attempt)).isEqualTo(3);
        verify(gateway, never()).lookup(any());
        verify(gateway, never()).create(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 204})
    @DisplayName("수동 대사는 요청/계약 오류 사유를 구분하며 단일 조회로 끝낸다")
    void recordsNonRetryableLookupReason(int status) {
        PaymentAttempt attempt = prepared();
        doAnswer(invocation -> PaymentObservation.httpFailure(status)).when(gateway).lookup(any());
        ConfirmOrderResult result = reconcile.execute(command(attempt));
        assertThat(result.paymentAttempt().lastReason()).isEqualTo(status == 403
            ? PaymentRecoveryTask.Reason.LOOKUP_REQUEST_ERROR : PaymentRecoveryTask.Reason.LOOKUP_CONTRACT_ERROR);
        assertThat(result.paymentAttempt().usedRounds()).isZero();
        assertThat(result.order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(stock(attempt)).isEqualTo(3);
        verify(gateway).lookup(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("저장한 성공/생성 전 거절은 PG 조회 없이 반영하고 완료 재요청은 인계하지 않는다")
    void reappliesStoredTerminalWithoutLookup(boolean success) {
        // arrange
        PaymentAttempt attempt = prepared();
        observations.execute(attempt.getAttemptKey(), success
            ? PaymentObservation.verified("tx-saved", attempt.getProviderOrderId(), attempt.getAmount(), ProviderPaymentStatus.SUCCESS)
            : PaymentObservation.rejected(attempt.getProviderOrderId(), attempt.getAmount()));
        // act
        ConfirmOrderResult result = reconcile.execute(command(attempt));
        var before = tasks.findByKey(attempt.getAttemptKey()).orElseThrow();
        if (success) {
            observations.execute(attempt.getAttemptKey(), PaymentObservation.verified("tx-saved",
                attempt.getProviderOrderId(), attempt.getAmount(), ProviderPaymentStatus.FAILED));
        }
        ConfirmOrderResult repeated = reconcile.execute(command(attempt));
        // assert
        assertThat(result.completed()).isTrue();
        assertThat(result.order().status()).isEqualTo(success ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED);
        assertThat(result.paymentAttempt().recoveryState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
        assertThat(repeated.completed()).isTrue();
        assertThat(repeated.attentionRequired()).isEqualTo(success);
        assertThat(tasks.findByKey(attempt.getAttemptKey()).orElseThrow().getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(stock(attempt)).isEqualTo(success ? 3 : 5);
        verify(gateway, never()).lookup(any());
        verify(gateway, never()).create(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "missing", "timeout"})
    @DisplayName("기존 4회 사용 작업도 인계·단일 조회하고 예산 유지·조회 시각·수동 사유만 저장한다")
    void keepsBudgetAndLooksUpOnce(String response) {
        // arrange
        PaymentAttempt attempt = prepared();
        String oldExecution = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            tasks.claim(attempt.getAttemptKey(), oldExecution, Instant.now());
            for (int round = 0; round < 4; round++) { tasks.consumeRound(attempt.getAttemptKey(), oldExecution, round, Instant.now()); }
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return switch (response) {
                case "pending" -> PaymentObservation.verified("tx-pending", attempt.getProviderOrderId(),
                    attempt.getAmount(), ProviderPaymentStatus.PENDING);
                case "missing" -> PaymentObservation.missing();
                default -> PaymentObservation.unavailable(PaymentObservation.Reason.TIMEOUT);
            };
        }).when(gateway).lookup(any());
        // act
        ConfirmOrderResult result = reconcile.execute(command(attempt));
        // assert
        var task = tasks.findByKey(attempt.getAttemptKey()).orElseThrow();
        assertThat(task.getExecutionId()).isNotEqualTo(oldExecution);
        assertThat(task.getUsedRounds()).isEqualTo(4);
        assertThat(task.getLastCheckedAt()).isNotNull();
        assertThat(task.getState()).isEqualTo(PaymentRecoveryTask.State.MANUAL_REQUIRED);
        assertThat(task.getLastReason()).isEqualTo(response.equals("pending")
            ? PaymentRecoveryTask.Reason.PENDING : PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE);
        assertThat(result.completed()).isFalse();
        assertThat(result.attentionRequired()).isTrue();
        assertThat(result.order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(stock(attempt)).isEqualTo(3);
        verify(gateway).lookup(new PaymentProviderGateway.ExpectedPayment(attempt.getProviderOrderId(), attempt.getAmount(), null));
        verify(gateway, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("조회 중 새 수동 실행이 인계하면 늦은 이전 실행의 진행 저장은 새 사유·회차·실행 ID를 덮지 않는다")
    void protectsHandoverFromLateLookup() throws Exception {
        PaymentAttempt attempt = prepared();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return PaymentObservation.verified("tx-late", attempt.getProviderOrderId(), attempt.getAmount(), ProviderPaymentStatus.PENDING);
            }
            return PaymentObservation.missing();
        }).when(gateway).lookup(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var old = executor.submit(() -> reconcile.execute(command(attempt)));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                reconcile.execute(command(attempt));
                var next = tasks.findByKey(attempt.getAttemptKey()).orElseThrow();
                release.countDown();
                old.get(10, TimeUnit.SECONDS);
                var after = tasks.findByKey(attempt.getAttemptKey()).orElseThrow();
                assertThat(after.snapshot()).isEqualTo(next.snapshot());
                assertThat(after.getLastReason()).isEqualTo(PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE);
                assertThat(after.getUpdatedAt()).isEqualTo(next.getUpdatedAt());
                assertThat(stock(attempt)).isEqualTo(3);
            } finally { release.countDown(); }
        }
    }

    private PaymentAttempt prepared() {
        Brand brand = brands.save(Brand.create("브랜드", null));
        Product product = products.save(Product.create(brand.getId(), "상품", null, 1000, 5));
        Order order = orders.insert(Order.create(2L, List.of(OrderItem.create(product.getId(), "상품", 1000, 2))));
        return prepare.execute(new PrepareOrderPaymentCommand(2L, order.getId(), UUID.randomUUID().toString(),
            PaymentScenario.resolve(null, null))).attempt();
    }

    private ReconcileOrderPaymentCommand command(PaymentAttempt attempt) {
        return new ReconcileOrderPaymentCommand(2L, attempt.getOrderId(), false);
    }

    private int stock(PaymentAttempt attempt) {
        long productId = orders.findById(attempt.getOrderId()).orElseThrow().getItems().get(0).getProductId();
        return products.findById(productId).orElseThrow().getStock();
    }
}
