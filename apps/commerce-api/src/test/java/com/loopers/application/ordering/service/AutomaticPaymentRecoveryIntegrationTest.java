package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.loopers.application.ordering.command.PrepareOrderPaymentCommand;
import com.loopers.application.ordering.support.AutomaticPaymentExecution;
import com.loopers.application.ordering.support.RepeatPaymentLookupException;
import com.loopers.application.ordering.usecase.PrepareOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.RecoverAutomaticPaymentUseCase;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.gateway.PaymentRecoveryExecutor;
import com.loopers.application.pay.gateway.PaymentRecoveryRuntime;
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
import com.loopers.support.test.OrderConfirmProbe;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
class AutomaticPaymentRecoveryIntegrationTest {
    private static final String RETRY = "payment-auto-lookup";
    @Autowired private RecoverAutomaticPaymentUseCase recovery;
    @Autowired private AutomaticPaymentStateService states;
    @Autowired private PrepareOrderPaymentUseCase preparation;
    @Autowired private RecordPaymentObservationUseCase observations;
    @Autowired private PaymentRecoveryRuntime runtime;
    @Autowired private PaymentRecoveryExecutor executor;
    @Autowired @Qualifier("paymentRecoveryExecutor") private ThreadPoolTaskExecutor threads;
    @Autowired private PaymentProviderGateway provider;
    @Autowired private RetryRegistry retries;
    @Autowired private MeterRegistry meters;
    @Autowired private CircuitBreakerRegistry circuits;
    @Autowired private OrderRepository orders;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private PaymentRecoveryTaskRepository tasks;
    @Autowired private TransactionTemplate tx;
    @Autowired private OrderConfirmProbe probe;
    @Autowired private DatabaseCleanUp cleanup;
    private Retry productionRetry;

    @BeforeEach
    void arrangeFastIntervals() {
        circuits.circuitBreaker("pg-lookup").reset();
        productionRetry = retries.retry(RETRY);
        // 이 이름의 실제 프록시 검사만 대기를 없앤다. 운영 간격은 별도 검사하고 전역 sleep은 바꾸지 않는다.
        IntervalBiFunction<Object> immediate = (round, result) -> 0L;
        RetryConfig config = RetryConfig.from(productionRetry.getRetryConfig()).intervalBiFunction(immediate).build();
        retries.replace(RETRY, Retry.of(RETRY, config));
    }

    @AfterEach
    void clean() {
        retries.replace(RETRY, productionRetry);
        circuits.circuitBreaker("pg-lookup").reset();
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("실제 Retry 프록시는 최대 4회 사용 후 수동 전환하고 차감 재고와 생성 횟수를 유지한다")
    void exhaustsFourLookups() {
        PaymentAttempt attempt = prepared();
        var execution = claimed(attempt);
        assertThat(states.claim(attempt.getAttemptKey(), UUID.randomUUID().toString())).isEmpty();
        doReturn(verified(attempt, ProviderPaymentStatus.PENDING)).when(provider).lookup(any());
        assertThat(AopUtils.isAopProxy(recovery)).isTrue();
        recovery.execute(execution);
        assertThat(task(attempt).getUsedRounds()).isEqualTo(4);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.MANUAL_REQUIRED);
        assertThat(task(attempt).getLastReason()).isEqualTo(PaymentRecoveryTask.Reason.RETRY_EXHAUSTED);
        assertThat(stock(attempt)).isEqualTo(3);
        assertThat(orders.findById(attempt.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(retries.retry(RETRY).getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
        assertThat(meters.find("resilience4j.retry.calls").tag("name", RETRY).meters()).isNotEmpty();
        verify(provider, times(4)).lookup(any());
        verify(provider, never()).create(any(), any(), any());
        assertThat(productionRetry.getRetryConfig().getMaxAttempts()).isEqualTo(4);
        var oneRound = new AutomaticPaymentExecution(new PaymentRecoveryTask.Snapshot(attempt.getAttemptKey(),
            PaymentRecoveryTask.State.RUNNING, execution.snapshot().executionId(), 1), runtime.nanoTime());
        long delay = productionRetry.getRetryConfig().getIntervalBiFunction().apply(1,
            Either.left(new RepeatPaymentLookupException(oneRound)));
        assertThat(delay).isBetween(800L, 1200L);
        assertThat(productionRetry.getRetryConfig().getExceptionPredicate().test(new IllegalStateException("DB 실패"))).isFalse();
        assertThat(productionRetry.getRetryConfig().getExceptionPredicate().test(new RepeatPaymentLookupException(execution))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ProviderPaymentStatus.class, names = {"SUCCESS", "FAILED"})
    @DisplayName("처리 중 뒤 종결 결과는 관찰 commit 후 한 번 반영하고 남은 자동 조회를 중단한다")
    void completesBeforeExhaustion(ProviderPaymentStatus outcome) {
        PaymentAttempt attempt = prepared();
        doReturn(verified(attempt, ProviderPaymentStatus.PENDING), verified(attempt, outcome)).when(provider).lookup(any());
        recovery.execute(claimed(attempt));
        assertThat(task(attempt).getUsedRounds()).isEqualTo(2);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
        boolean success = outcome == ProviderPaymentStatus.SUCCESS;
        assertThat(stock(attempt)).isEqualTo(success ? 3 : 5);
        assertThat(probe.countPaidOrderRecords(attempt.getOrderId())).isEqualTo(success ? 1 : 0);
        assertThat(retries.retry(RETRY).getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
        verify(provider, times(2)).lookup(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("회로 차단 또는 요청 오류는 확보한 회차를 유지하고 즉시 수동 전환한다")
    void stopsOnCircuitOrPermanentRequestError(boolean open) {
        PaymentAttempt attempt = prepared();
        if (open) { circuits.circuitBreaker("pg-lookup").transitionToOpenState(); }
        else { doReturn(PaymentObservation.httpFailure(403)).when(provider).lookup(any()); }
        recovery.execute(claimed(attempt));
        assertThat(task(attempt).getUsedRounds()).isEqualTo(1);
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.MANUAL_REQUIRED);
        assertThat(task(attempt).getLastReason()).isEqualTo(open ? PaymentRecoveryTask.Reason.CIRCUIT_BLOCKED
            : PaymentRecoveryTask.Reason.LOOKUP_REQUEST_ERROR);
        assertThat(stock(attempt)).isEqualTo(3);
        assertThat(circuits.circuitBreaker("pg-lookup").getMetrics().getNumberOfBufferedCalls()).isZero();
        verify(provider, times(open ? 0 : 1)).lookup(any());
    }

    @Test
    @DisplayName("큐 대기 사이 종결 근거가 저장되면 PG 조회·회차 소비 없이 저장 결과만 반영한다")
    void appliesSavedFactBeforeAnotherLookup() {
        PaymentAttempt attempt = prepared();
        var execution = claimed(attempt);
        observations.execute(attempt.getAttemptKey(), verified(attempt, ProviderPaymentStatus.SUCCESS));
        recovery.execute(execution);
        assertThat(task(attempt).getUsedRounds()).isZero();
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
        verify(provider, never()).lookup(any());
    }

    @Test
    @DisplayName("실행기 두 작업·대기 두 작업이 차면 다섯 번째는 호출자 실행 없이 명확히 거절한다")
    void boundsExecutorCapacity() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(4);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        Runnable job = () -> {
            started.countDown();
            try {
                assertThat(Thread.currentThread().getName()).startsWith("payment-recovery-");
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); failures.add(failure); }
            catch (Throwable failure) { failures.add(failure); }
            finally { completed.countDown(); }
        };
        try {
            executor.submit(job); executor.submit(job);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(job); executor.submit(job);
            assertThat(threads.getThreadPoolExecutor().getQueue()).hasSize(2);
            assertThatThrownBy(() -> executor.submit(job)).isInstanceOf(PaymentRecoveryExecutor.SubmissionRejectedException.class);
        } finally { release.countDown(); }
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
    }

    private PaymentAttempt prepared() {
        Brand brand = brands.save(Brand.create("브랜드", null));
        Product product = products.save(Product.create(brand.getId(), "상품", null, 1000, 5));
        Order order = orders.insert(Order.create(2L, List.of(OrderItem.create(product.getId(), "상품", 1000, 2))));
        return preparation.execute(new PrepareOrderPaymentCommand(2L, order.getId(), UUID.randomUUID().toString(),
            PaymentScenario.resolve(null, null))).attempt();
    }

    private AutomaticPaymentExecution claimed(PaymentAttempt attempt) {
        var snapshot = states.claim(attempt.getAttemptKey(), UUID.randomUUID().toString()).orElseThrow();
        assertThat(task(attempt).getState()).isEqualTo(PaymentRecoveryTask.State.RUNNING);
        return new AutomaticPaymentExecution(snapshot, runtime.nanoTime());
    }

    private PaymentObservation verified(PaymentAttempt attempt, ProviderPaymentStatus status) {
        return PaymentObservation.verified("PG-" + attempt.getOrderId(), attempt.getProviderOrderId(), attempt.getAmount(), status);
    }

    private PaymentRecoveryTask task(PaymentAttempt attempt) {
        return tx.execute(ignored -> tasks.findByKey(attempt.getAttemptKey()).orElseThrow());
    }

    private int stock(PaymentAttempt attempt) {
        long id = orders.findById(attempt.getOrderId()).orElseThrow().getItems().getFirst().getProductId();
        return products.findById(id).orElseThrow().getStock();
    }
}
