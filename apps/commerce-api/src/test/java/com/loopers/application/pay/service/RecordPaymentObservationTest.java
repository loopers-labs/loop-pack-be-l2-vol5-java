package com.loopers.application.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class RecordPaymentObservationTest {
    @Autowired
    RecordPaymentObservationUseCase recordObservation;
    @Autowired
    PaymentAttemptRepository attempts;
    @Autowired
    PaymentRecoveryTaskRepository tasks;
    @Autowired
    StockAdjustmentRepository stocks;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    EntityManager entityManager;

    @ParameterizedTest
    @EnumSource(value = ProviderPaymentStatus.class, names = {"PENDING", "FAILED"})
    @DisplayName("동시 관찰을 모두 저장하고 종결 결과 또는 상충 근거를 보존한다")
    void concurrentObservations(ProviderPaymentStatus otherStatus) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String key = UUID.randomUUID().toString();
        PaymentAttempt prepared = PaymentAttempt.create(900000020L + otherStatus.ordinal(), key, 7000,
                PaymentScenario.resolve(null, null)).startDispatch();
        tx.executeWithoutResult(status -> attempts.insert(prepared));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of(ProviderPaymentStatus.SUCCESS, otherStatus).stream().map(result -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 관찰 시작 대기 초과");
                }
                return recordObservation.execute(key, PaymentObservation.verified("PG-" + key,
                        prepared.getProviderOrderId(), 7000, result));
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(15, TimeUnit.SECONDS)).isNotNull();
            }
            tx.executeWithoutResult(status -> {
                PaymentAttempt saved = attempts.findByKey(key).orElseThrow();
                if (otherStatus == ProviderPaymentStatus.PENDING) {
                    assertThat(saved.getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
                    assertThat(saved.getConflict()).isEqualTo(PaymentAttempt.Conflict.NONE);
                } else {
                    assertThat(saved.getOutcome()).isIn(PaymentAttempt.Outcome.SUCCESS, PaymentAttempt.Outcome.FAILED);
                    assertThat(saved.getConflict()).isEqualTo(PaymentAttempt.Conflict.TERMINAL_CONFLICT);
                }
            });
        } finally {
            start.countDown();
            tx.executeWithoutResult(status -> entityManager.createQuery(
                    "delete from PaymentAttemptJpaEntity a where a.attemptKey = :key").setParameter("key", key).executeUpdate());
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentObservation.Kind.class, names = {"VERIFIED", "REJECTED_NO_EFFECT"})
    @DisplayName("후속 업무 기록을 rollback해도 먼저 저장한 성공·거절 근거는 남는다")
    void preservesEvidenceAcrossBusinessRollback(PaymentObservation.Kind kind) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long orderId = 900000010L + kind.ordinal();
        String key = UUID.randomUUID().toString();
        PaymentAttempt prepared = PaymentAttempt.create(orderId, key, 7000, PaymentScenario.resolve(null, null)).startDispatch();
        tx.executeWithoutResult(status -> {
            attempts.insert(prepared);
            tasks.insert(PaymentRecoveryTask.create(key));
            stocks.insert(StockAdjustment.create(orderId, List.of(new StockAdjustment.Item(1, 2))));
        });
        PaymentObservation evidence = kind == PaymentObservation.Kind.VERIFIED
                ? PaymentObservation.verified("PG-" + UUID.randomUUID(), prepared.getProviderOrderId(), 7000, ProviderPaymentStatus.SUCCESS)
                : PaymentObservation.rejected(prepared.getProviderOrderId(), 7000);
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                recordObservation.execute(key, evidence);
                StockAdjustment adjustment = stocks.findByOrderId(orderId).orElseThrow();
                if (kind == PaymentObservation.Kind.VERIFIED) {
                    adjustment.consume();
                } else {
                    adjustment.restore();
                }
                stocks.save(adjustment);
                tasks.markApplied(key, Instant.now());
                throw new IllegalStateException("후속 업무 실패 재현");
            })).isInstanceOf(IllegalStateException.class);

            tx.executeWithoutResult(status -> {
                PaymentAttempt restored = attempts.findByKey(key).orElseThrow();
                assertThat(restored.getOutcome()).isEqualTo(kind == PaymentObservation.Kind.VERIFIED
                        ? PaymentAttempt.Outcome.SUCCESS : PaymentAttempt.Outcome.FAILED);
                assertThat(restored.getTransactionKey()).isEqualTo(evidence.transactionKey());
                assertThat(tasks.findByKey(key).orElseThrow().getState()).isEqualTo(PaymentRecoveryTask.State.READY);
                assertThat(stocks.findByOrderId(orderId).orElseThrow().getState()).isEqualTo(StockAdjustment.State.DEDUCTED);
            });
            PaymentAttempt unchanged = recordObservation.execute(key,
                    PaymentObservation.unavailable(PaymentObservation.Reason.TIMEOUT));
            assertThat(unchanged.getOutcome()).isNotEqualTo(PaymentAttempt.Outcome.UNKNOWN);
        } finally {
            tx.executeWithoutResult(status -> {
                entityManager.createQuery("delete from StockAdjustmentItemJpaEntity i where i.adjustment.orderId = :id")
                        .setParameter("id", orderId).executeUpdate();
                entityManager.createQuery("delete from StockAdjustmentJpaEntity a where a.orderId = :id")
                        .setParameter("id", orderId).executeUpdate();
                entityManager.createQuery("delete from PaymentRecoveryTaskJpaEntity t where t.attemptKey = :key")
                        .setParameter("key", key).executeUpdate();
                entityManager.createQuery("delete from PaymentAttemptJpaEntity a where a.attemptKey = :key")
                        .setParameter("key", key).executeUpdate();
            });
        }
    }
}
