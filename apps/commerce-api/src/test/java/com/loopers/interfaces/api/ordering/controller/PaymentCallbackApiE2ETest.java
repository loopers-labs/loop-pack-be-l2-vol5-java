package com.loopers.interfaces.api.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.loopers.application.ordering.command.PrepareOrderPaymentCommand;
import com.loopers.application.ordering.command.ReconcileOrderPaymentCommand;
import com.loopers.application.ordering.usecase.PrepareOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.ReconcileOrderPaymentUseCase;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import com.loopers.support.test.E2ETest;
import com.loopers.support.test.OrderConfirmProbe;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@E2ETest
class PaymentCallbackApiE2ETest {
    @Autowired private TestRestTemplate http;
    @Autowired private PrepareOrderPaymentUseCase prepare;
    @Autowired private ReconcileOrderPaymentUseCase reconcile;
    @Autowired private RecordPaymentObservationUseCase observations;
    @Autowired private PaymentProviderGateway gateway;
    @Autowired private OrderRepository orders;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentRecoveryTaskRepository tasks;
    @Autowired private StockAdjustmentRepository adjustments;
    @Autowired private OrderConfirmProbe probe;
    @Autowired private TransactionTemplate tx;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private CircuitBreakerRegistry circuits;

    @BeforeEach void resetCircuit() { circuits.circuitBreaker("pg-lookup").reset(); }
    @AfterEach void clean() { cleanup.truncateAllTables(); circuits.circuitBreaker("pg-lookup").reset(); }

    @Test
    @DisplayName("성공 알림이어도 PG가 PENDING이면 202·재고 유지이며 알림 키는 검증 뒤에만 연결한다")
    void trustsLookupInsteadOfSuccessNotification() {
        PaymentAttempt attempt = prepared();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            PaymentProviderGateway.ExpectedPayment expected = invocation.getArgument(0);
            assertThat(expected.transactionKey()).isNull();
            return verified(attempt, ProviderPaymentStatus.PENDING);
        }).when(gateway).lookup(any());
        ResponseEntity<String> response = callback(attempt, "SUCCESS");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"completed\":false").doesNotContain("orderId", "items", "userId", "amount");
        assertThat(saved(attempt).getOutcome()).isEqualTo(PaymentAttempt.Outcome.PENDING);
        assertThat(saved(attempt).getTransactionKey()).isEqualTo(key(attempt));
        assertThat(tasks.findByKey(attempt.getAttemptKey()).orElseThrow().getState()).isEqualTo(PaymentRecoveryTask.State.READY);
        assertThat(stock(attempt)).isEqualTo(3);
        verify(gateway).lookup(any());
        verify(gateway, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("실패 알림도 PG 성공으로 한 번 확정하고 늦은 PG 종결 충돌은 완료·주의로 보존한다")
    void appliesAuthorityOnceAndPreservesLateConflict() {
        PaymentAttempt attempt = prepared();
        observePending(attempt);
        doReturn(verified(attempt, ProviderPaymentStatus.SUCCESS)).when(gateway).lookup(any());
        assertThat(callback(attempt, "FAILED").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(callback(attempt, "SUCCESS").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isEqualTo(1);
        assertThat(probe.countUsePointBills(2L, attempt.getOrderId())).isZero();
        assertThat(stock(attempt)).isEqualTo(3);
        doReturn(verified(attempt, ProviderPaymentStatus.FAILED)).when(gateway).lookup(any());
        ResponseEntity<String> late = callback(attempt, "FAILED");
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(late.getBody()).contains("\"completed\":true", "\"attentionRequired\":true");
        assertThat(saved(attempt).getConflict()).isEqualTo(PaymentAttempt.Conflict.TERMINAL_CONFLICT);
        assertThat(circuits.circuitBreaker("pg-lookup").getMetrics().getNumberOfBufferedCalls()).isEqualTo(2);
        assertThat(probe.orderStatus(attempt.getOrderId())).isEqualTo("CONFIRMED");
        assertThat(stock(attempt)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("알림을 받았어도 PG 조회의 timeout/거래 없음은 실패 확정·재고 복구 근거가 아니다")
    void keepsUnknownOnUnavailableLookup(boolean timeout) {
        PaymentAttempt attempt = prepared();
        doReturn(timeout ? PaymentObservation.unavailable(PaymentObservation.Reason.TIMEOUT) : PaymentObservation.missing())
            .when(gateway).lookup(any());
        assertThat(callback(attempt, "SUCCESS").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(saved(attempt).getOutcome()).isEqualTo(PaymentAttempt.Outcome.UNKNOWN);
        assertThat(stock(attempt)).isEqualTo(3);
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isZero();
        verify(gateway).lookup(any());
    }

    @Test
    @DisplayName("잘못된 입력·미등록 주문·저장 거래 키와 다른 알림은 조회와 상태 변경 전에 거절한다")
    void rejectsInvalidOrUnregisteredNotification() {
        PaymentAttempt attempt = prepared();
        observePending(attempt);
        for (Object body : List.of(Map.of("providerOrderId", attempt.getProviderOrderId()),
                Map.of("providerOrderId", 123456, "transactionKey", key(attempt), "status", "SUCCESS"),
                Map.of("providerOrderId", attempt.getProviderOrderId(), "transactionKey", key(attempt), "status", "TYPO"))) {
            assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(post(Map.of("providerOrderId", "unregistered-order", "transactionKey", "any-key", "status", "SUCCESS"))
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(Map.of("providerOrderId", attempt.getProviderOrderId(), "transactionKey", "another-key", "status", "SUCCESS"))
            .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(gateway, never()).lookup(any());
        assertThat(saved(attempt).getConflict()).isEqualTo(PaymentAttempt.Conflict.NONE);
        assertThat(stock(attempt)).isEqualTo(3);
    }

    @Test
    @DisplayName("저장 거래 키가 없어도 알림 키와 PG 조회 키가 다르면 연결·업무 반영하지 않는다")
    void rejectsUnboundKeyMismatch() {
        PaymentAttempt attempt = prepared();
        doReturn(verified(attempt, ProviderPaymentStatus.SUCCESS)).when(gateway).lookup(any());
        assertThat(post(Map.of("providerOrderId", attempt.getProviderOrderId(), "transactionKey", "forged-key", "status", "SUCCESS"))
            .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(saved(attempt).getTransactionKey()).isNull();
        assertThat(saved(attempt).getConflict()).isEqualTo(PaymentAttempt.Conflict.NONE);
        assertThat(stock(attempt)).isEqualTo(3);
    }

    @Test
    @DisplayName("중복 실패 callback과 수동 대사가 동시에 도착해도 차감 재고는 한 번만 복구한다")
    void restoresOnceWithConcurrentCallbacksAndManualReconcile() throws Exception {
        PaymentAttempt attempt = prepared();
        observePending(attempt);
        CountDownLatch allLookedUp = new CountDownLatch(3);
        doAnswer(invocation -> {
            allLookedUp.countDown();
            assertThat(allLookedUp.await(10, TimeUnit.SECONDS)).isTrue();
            return verified(attempt, ProviderPaymentStatus.FAILED);
        }).when(gateway).lookup(any());
        try (var executor = Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> callback(attempt, "FAILED"));
            var second = executor.submit(() -> callback(attempt, "FAILED"));
            var manual = executor.submit(() -> reconcile.execute(new ReconcileOrderPaymentCommand(2L, attempt.getOrderId(), false)));
            assertThat(first.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(manual.get(15, TimeUnit.SECONDS).completed()).isTrue();
        }
        assertThat(stock(attempt)).isEqualTo(5);
        assertThat(probe.orderStatus(attempt.getOrderId())).isEqualTo("PAYMENT_FAILED");
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isZero();
        StockAdjustment.State adjustmentState = tx.execute(status ->
            adjustments.findByOrderId(attempt.getOrderId()).orElseThrow().getState());
        assertThat(adjustmentState).isEqualTo(StockAdjustment.State.RESTORED);
        var task = tasks.findByKey(attempt.getAttemptKey()).orElseThrow();
        assertThat(task.getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
        assertThat(task.getUsedRounds()).isZero();
        verify(gateway, times(3)).lookup(any());
    }

    @Test
    @DisplayName("callback 조회 중 수동 인계 뒤 업무 실패가 나도 새 실행을 수동 실패로 덮지 않고 PG 성공 근거는 남긴다")
    void preservesNewExecutionWhenBusinessApplicationFails() {
        PaymentAttempt attempt = prepared();
        String nextId = UUID.randomUUID().toString();
        doAnswer(invocation -> {
            tx.executeWithoutResult(status -> assertThat(tasks.claim(attempt.getAttemptKey(), nextId, Instant.now())).isTrue());
            return verified(attempt, ProviderPaymentStatus.SUCCESS);
        }).when(gateway).lookup(any());
        tx.executeWithoutResult(status -> doThrow(new IllegalStateException("업무 반영 실패")).when(orders)
            .completePgIfPending(anyLong(), eq(OrderStatus.CONFIRMED), any()));
        ResponseEntity<String> response = callback(attempt, "SUCCESS");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"attentionRequired\":true");
        var task = tasks.findByKey(attempt.getAttemptKey()).orElseThrow();
        assertThat(task.getState()).isEqualTo(PaymentRecoveryTask.State.RUNNING);
        assertThat(task.getExecutionId()).isEqualTo(nextId);
        assertThat(task.getLastReason()).isEqualTo(PaymentRecoveryTask.Reason.NONE);
        assertThat(saved(attempt).getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
        assertThat(stock(attempt)).isEqualTo(3);
        assertThat(probe.orderStatus(attempt.getOrderId())).isEqualTo("PAYMENT_PENDING");
    }

    private PaymentAttempt prepared() {
        Brand brand = brands.save(Brand.create("브랜드", null));
        Product product = products.save(Product.create(brand.getId(), "상품", null, 1000, 5));
        Order order = orders.insert(Order.create(2L, List.of(OrderItem.create(product.getId(), "상품", 1000, 2))));
        return prepare.execute(new PrepareOrderPaymentCommand(2L, order.getId(), UUID.randomUUID().toString(),
            PaymentScenario.resolve(null, null))).attempt();
    }

    private void observePending(PaymentAttempt attempt) { observations.execute(attempt.getAttemptKey(), verified(attempt, ProviderPaymentStatus.PENDING)); }
    private String key(PaymentAttempt attempt) { return "tx-" + attempt.getOrderId(); }
    private PaymentObservation verified(PaymentAttempt attempt, ProviderPaymentStatus status) {
        return PaymentObservation.verified(key(attempt), attempt.getProviderOrderId(), attempt.getAmount(), status);
    }
    private PaymentAttempt saved(PaymentAttempt attempt) {
        return tx.execute(status -> attempts.findByKey(attempt.getAttemptKey()).orElseThrow());
    }
    private int stock(PaymentAttempt attempt) {
        long productId = orders.findById(attempt.getOrderId()).orElseThrow().getItems().get(0).getProductId();
        return products.findById(productId).orElseThrow().getStock();
    }
    private ResponseEntity<String> callback(PaymentAttempt attempt, String status) {
        return post(Map.of("providerOrderId", attempt.getProviderOrderId(), "transactionKey", key(attempt), "status", status));
    }
    private ResponseEntity<String> post(Object body) { return http.postForEntity("/api/v1/payments/callback", body, String.class); }
}
