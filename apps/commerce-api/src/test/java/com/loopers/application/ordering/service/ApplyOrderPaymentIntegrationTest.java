package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.loopers.application.ordering.command.PrepareOrderPaymentCommand;
import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.PrepareOrderPaymentUseCase;
import com.loopers.application.pay.service.PaymentRecoveryProgressService;
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
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import com.loopers.support.test.IntegrationTest;
import com.loopers.support.test.OrderConfirmProbe;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class ApplyOrderPaymentIntegrationTest {
    @Autowired private ApplyOrderPaymentUseCase apply;
    @Autowired private ApplyOrderPaymentService application;
    @Autowired private PaymentRecoveryProgressService progress;
    @Autowired private PrepareOrderPaymentUseCase prepare;
    @Autowired private RecordPaymentObservationUseCase observations;
    @Autowired private OrderRepository orders;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentRecoveryTaskRepository tasks;
    @Autowired private StockAdjustmentRepository adjustments;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderConfirmProbe probe;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired private RetryOrderPaymentService retry;
    @Autowired private RetryRegistry retries;
    @Autowired private CircuitBreakerRegistry circuits;
    @Autowired private PaymentProviderGateway provider;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void tearDown() {
        reset(orders);
        circuits.circuitBreaker("pg-lookup").reset();
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("성공은 삭제 상품도 추가 차감 없이 한 번 확정하고 늦은 충돌에도 완료와 주의를 함께 반환한다")
    void appliesSuccessOnceAndPreservesLateConflict() throws Exception {
        PaymentAttempt attempt = prepared(5, 2);
        long productId = productId(attempt);
        tx().executeWithoutResult(status -> {
            Product product = products.findByIdForUpdate(productId).orElseThrow();
            product.delete();
            products.save(product);
        });
        observe(attempt, ProviderPaymentStatus.SUCCESS);

        assertThat(race(() -> apply.execute(attempt.getAttemptKey()))).allSatisfy(result ->
            assertThat(result.state()).isEqualTo(ApplyOrderPaymentResult.State.APPLIED));

        Order order = orders.findById(attempt.getOrderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getRecord().orElseThrow().getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(probe.countPaidOrderRecords(order.getId())).isEqualTo(1L);
        assertThat(probe.countUsePointBills(1L, order.getId())).isZero();
        assertThat(products.findById(productId).orElseThrow().getStock()).isEqualTo(3);
        assertThat(adjustment(attempt).getState()).isEqualTo(StockAdjustment.State.CONSUMED);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
        observe(attempt, ProviderPaymentStatus.FAILED);
        ApplyOrderPaymentResult late = apply.execute(attempt.getAttemptKey());
        assertThat(late.state()).isEqualTo(ApplyOrderPaymentResult.State.APPLIED);
        assertThat(late.attentionRequired()).isTrue();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(attempt(attempt).getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
    }

    @Test
    @DisplayName("동시 실패 반영은 관리자 변경 뒤 삭제된 상품에 한 번만 반환하고 실패·복구 완료를 함께 저장한다")
    void restoresFailureOnceToCurrentStock() throws Exception {
        PaymentAttempt attempt = prepared(5, 2);
        long productId = productId(attempt);
        tx().executeWithoutResult(status -> {
            Product product = products.findByIdForUpdate(productId).orElseThrow();
            product.setStock(10);
            product.delete();
            products.save(product);
        });
        observe(attempt, ProviderPaymentStatus.FAILED);

        race(() -> apply.execute(attempt.getAttemptKey()));

        Product restored = products.findById(productId).orElseThrow();
        assertThat(restored.getStock()).isEqualTo(12);
        assertThat(restored.isDeleted()).isTrue();
        assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isZero();
        assertThat(adjustment(attempt).getState()).isEqualTo(StockAdjustment.State.RESTORED);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
    }

    @Test
    @DisplayName("생성 전 확정 거절은 거래 키 없이 저장 근거만으로 실패와 반환을 반영한다")
    void reappliesStoredNoEffectRejection() {
        PaymentAttempt attempt = prepared(5, 2);
        observations.execute(attempt.getAttemptKey(), PaymentObservation.rejected(attempt.getProviderOrderId(), attempt.getAmount()));

        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.APPLIED);

        assertThat(attempt(attempt).getTransactionKey()).isNull();
        assertThat(attempt(attempt).getRejection()).isEqualTo(PaymentAttempt.Rejection.NO_EFFECT);
        assertThat(products.findById(productId(attempt)).orElseThrow().getStock()).isEqualTo(5);
        assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    @DisplayName("UNKNOWN·PENDING·미반영 충돌은 주문과 차감 상태를 유지한다")
    void leavesUnresolvedAndConflictingResultsUnapplied() {
        PaymentAttempt attempt = prepared(5, 2);
        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.PENDING);
        observe(attempt, ProviderPaymentStatus.PENDING);
        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.PENDING);
        observations.execute(attempt.getAttemptKey(), PaymentObservation.verified("different-key", attempt.getProviderOrderId(),
            attempt.getAmount(), ProviderPaymentStatus.SUCCESS));

        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.CONFLICT);
        assertThat(products.findById(productId(attempt)).orElseThrow().getStock()).isEqualTo(3);
        assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(adjustment(attempt).getState()).isEqualTo(StockAdjustment.State.DEDUCTED);
        assertThat(task(attempt).getState()).isNotEqualTo(PaymentRecoveryTask.State.APPLIED);
    }

    @Test
    @DisplayName("성공 기록 SQL 뒤 업무 실패는 성공 근거만 보존하고 READY를 수동 표시한 뒤 저장 근거로 재적용한다")
    void preservesSuccessAcrossBusinessRollback() {
        PaymentAttempt attempt = prepared(5, 2);
        observe(attempt, ProviderPaymentStatus.SUCCESS);
        failAfterOrderSql(attempt);

        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.APPLICATION_FAILED);

        assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isZero();
        assertThat(attempt(attempt).getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
        assertThat(adjustment(attempt).getState()).isEqualTo(StockAdjustment.State.DEDUCTED);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.MANUAL_REQUIRED);
        assertThat(task(attempt).getExecutionId()).isNotNull();
        assertThat(task(attempt).getLastReason()).isEqualTo(PaymentRecoveryTask.Reason.APPLICATION_FAILED);
        verify(orders, times(1)).completePgIfPending(eq(attempt.getOrderId()), any(), any());
        reset(orders);
        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.APPLIED);
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("반환 overflow는 실패 근거·선차감을 유지하고 원인 해소 후 실패 근거를 다시 반영한다")
    void marksOverflowForManualAndReapplies() {
        PaymentAttempt attempt = prepared(5, 2);
        setStock(attempt, Integer.MAX_VALUE);
        observe(attempt, ProviderPaymentStatus.FAILED);

        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.APPLICATION_FAILED);

        assertThat(products.findById(productId(attempt)).orElseThrow().getStock()).isEqualTo(Integer.MAX_VALUE);
        assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(attempt(attempt).getOutcome()).isEqualTo(PaymentAttempt.Outcome.FAILED);
        assertThat(adjustment(attempt).getState()).isEqualTo(StockAdjustment.State.DEDUCTED);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.MANUAL_REQUIRED);
        setStock(attempt, 10);
        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.APPLIED);
        assertThat(products.findById(productId(attempt)).orElseThrow().getStock()).isEqualTo(12);
    }

    @Test
    @DisplayName("READY·RUNNING에서 실패한 옛 업무의 표시는 이후 인계 실행과 APPLIED를 덮지 않는다")
    void fencesFailureMarkerWithOriginalSnapshot() {
        PaymentAttempt attempt = prepared(5, 2);
        observe(attempt, ProviderPaymentStatus.SUCCESS);
        failAfterOrderSql(attempt);
        PaymentRecoveryTask.Snapshot ready = progress.capture(attempt.getAttemptKey());
        assertThatThrownBy(() -> application.apply(attempt.getAttemptKey()))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        String first = UUID.randomUUID().toString();
        tx().executeWithoutResult(status -> tasks.claim(attempt.getAttemptKey(), first, Instant.now()));
        assertThat(progress.markApplicationFailed(ready)).isFalse();
        PaymentRecoveryTask.Snapshot running = progress.capture(attempt.getAttemptKey());
        assertThatThrownBy(() -> application.apply(attempt.getAttemptKey()))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        String next = UUID.randomUUID().toString();
        tx().executeWithoutResult(status -> {
            assertThat(tasks.handover(attempt.getAttemptKey(), running.state(), first, 0, next, Instant.now())).isTrue();
            assertThat(tasks.consumeRound(attempt.getAttemptKey(), next, 0, Instant.now())).isTrue();
        });

        assertThat(progress.markApplicationFailed(running)).isFalse();
        assertThat(task(attempt).getExecutionId()).isEqualTo(next);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.RUNNING);
        assertThat(task(attempt).getUsedRounds()).isEqualTo(1);
        reset(orders);
        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.APPLIED);
        assertThat(progress.markApplicationFailed(running)).isFalse();
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderPaymentStatus.class, names = {"SUCCESS", "FAILED"})
    @DisplayName("코드로 식별한 데드락의 실제 rollback 뒤 새 TX로 한 번 더 반영하고 PG·조회 회차를 늘리지 않는다")
    void retriesOneDeadlockInNewTransaction(ProviderPaymentStatus outcome) {
        PaymentAttempt attempt = prepared(5, 2);
        observe(attempt, outcome);
        AtomicInteger calls = new AtomicInteger();
        var holders = new ArrayList<Object>();
        tx().executeWithoutResult(status -> doAnswer(invocation -> {
            holders.add(TransactionSynchronizationManager.getResource(entityManagerFactory));
            if (calls.incrementAndGet() == 2) {
                assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isZero();
            }
            Object result = invocation.callRealMethod();
            if (calls.get() == 1) { throw deadlock(); }
            return result;
        }).when(orders).completePgIfPending(eq(attempt.getOrderId()), any(), any()));

        assertThat(AopUtils.isAopProxy(retry)).isTrue();
        assertThat(apply.execute(attempt.getAttemptKey()).state()).isEqualTo(ApplyOrderPaymentResult.State.APPLIED);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(holders).hasSize(2).doesNotContainNull();
        assertThat(holders.get(1)).isNotSameAs(holders.getFirst());
        boolean success = outcome == ProviderPaymentStatus.SUCCESS;
        assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus())
            .isEqualTo(success ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED);
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isEqualTo(success ? 1 : 0);
        assertThat(products.findById(productId(attempt)).orElseThrow().getStock()).isEqualTo(success ? 3 : 5);
        assertThat(adjustment(attempt).getState()).isEqualTo(success ? StockAdjustment.State.CONSUMED : StockAdjustment.State.RESTORED);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
        assertThat(task(attempt).getUsedRounds()).isZero();
        assertThat(attempt(attempt).getOutcome().name()).isEqualTo(outcome.name());
        assertThat(circuits.circuitBreaker("pg-lookup").getMetrics().getNumberOfBufferedCalls()).isZero();
        verify(provider, never()).lookup(any());
        verify(provider, never()).create(any(), any(), any());
        var config = retries.retry("payment-application").getRetryConfig();
        assertThat(config.getMaxAttempts()).isEqualTo(2);
        assertThat(config.getIntervalBiFunction().apply(1, Either.left(deadlock()))).isEqualTo(100L);
    }

    @Test
    @DisplayName("데드락 2회 소진 뒤에도 최초 snapshot으로만 표시해 인계받은 실행·회차를 보호한다")
    void exhaustedDeadlocksCannotMarkNewOwner() {
        PaymentAttempt attempt = prepared(5, 2);
        observe(attempt, ProviderPaymentStatus.SUCCESS);
        String first = UUID.randomUUID().toString();
        String next = UUID.randomUUID().toString();
        tx().executeWithoutResult(status -> {
            assertThat(tasks.claim(attempt.getAttemptKey(), first, Instant.now())).isTrue();
            assertThat(tasks.consumeRound(attempt.getAttemptKey(), first, 0, Instant.now())).isTrue();
        });
        var original = progress.capture(attempt.getAttemptKey());
        AtomicInteger calls = new AtomicInteger();
        tx().executeWithoutResult(status -> doAnswer(invocation -> {
            invocation.callRealMethod();
            if (calls.incrementAndGet() == 2) {
                TransactionTemplate handover = tx();
                handover.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                handover.executeWithoutResult(ignored -> {
                    assertThat(tasks.handover(attempt.getAttemptKey(), original.state(), first, 1, next, Instant.now())).isTrue();
                    assertThat(tasks.consumeRound(attempt.getAttemptKey(), next, 1, Instant.now())).isTrue();
                });
            }
            throw deadlock();
        }).when(orders).completePgIfPending(eq(attempt.getOrderId()), any(), any()));

        assertThat(apply.execute(attempt.getAttemptKey(), original).state()).isEqualTo(ApplyOrderPaymentResult.State.APPLICATION_FAILED);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(task(attempt).getExecutionId()).isEqualTo(next);
        assertThat(task(attempt).getUsedRounds()).isEqualTo(2);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.RUNNING);
        assertThat(task(attempt).getLastReason()).isEqualTo(PaymentRecoveryTask.Reason.NONE);
        assertThat(attempt(attempt).getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isZero();
        assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(products.findById(productId(attempt)).orElseThrow().getStock()).isEqualTo(3);
        assertThat(adjustment(attempt).getState()).isEqualTo(StockAdjustment.State.DEDUCTED);
        verify(provider, never()).lookup(any());
        verify(provider, never()).create(any(), any(), any());
    }

    private CannotAcquireLockException deadlock() {
        // 코드가 있는 예외를 SQL 뒤 주입한다. 실제 TX rollback과 새 TX는 DB로 확인하며 DB victim 선정 실험은 후속 범위다.
        return new CannotAcquireLockException("주입한 데드락", new SQLException("deadlock", "40001", 1213));
    }

    private void failAfterOrderSql(PaymentAttempt attempt) {
        // MANDATORY TX proxy를 통과하는 spy 설정도 활성 TX 안에서 수행한다.
        tx().executeWithoutResult(status ->
            doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("업무 SQL 이후 실패"); })
                .when(orders).completePgIfPending(eq(attempt.getOrderId()), any(), any()));
    }

    private PaymentAttempt prepared(int stock, int quantity) {
        Brand brand = brands.save(Brand.create("브랜드", null));
        long productId = products.save(Product.create(brand.getId(), "상품", null, 1_000L, stock)).getId();
        long id = orders.insert(Order.create(1L, List.of(OrderItem.create(productId, "상품", 1_000L, quantity)))).getId();
        return prepare.execute(new PrepareOrderPaymentCommand(1L, id, UUID.randomUUID().toString(),
            PaymentScenario.resolve(null, null))).attempt();
    }

    private void observe(PaymentAttempt attempt, ProviderPaymentStatus status) {
        observations.execute(attempt.getAttemptKey(), PaymentObservation.verified("tx-" + attempt.getOrderId(),
            attempt.getProviderOrderId(), attempt.getAmount(), status));
    }

    private StockAdjustment adjustment(PaymentAttempt attempt) {
        return tx().execute(status -> adjustments.findByOrderId(attempt.getOrderId()).orElseThrow());
    }

    private long productId(PaymentAttempt attempt) { return adjustment(attempt).getItems().getFirst().productId(); }
    private PaymentAttempt attempt(PaymentAttempt attempt) {
        return tx().execute(status -> attempts.findByKey(attempt.getAttemptKey()).orElseThrow());
    }
    private PaymentRecoveryTask task(PaymentAttempt attempt) {
        return tx().execute(status -> tasks.findByKey(attempt.getAttemptKey()).orElseThrow());
    }
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private void setStock(PaymentAttempt attempt, int stock) {
        long productId = productId(attempt);
        tx().executeWithoutResult(status -> {
            Product product = products.findByIdForUpdate(productId).orElseThrow();
            product.setStock(stock);
            products.save(product);
        });
    }
    private <T> List<T> race(Callable<T> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<T> request = () -> { start.await(); return action.call(); };
            var a = executor.submit(request);
            var b = executor.submit(request);
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
}
