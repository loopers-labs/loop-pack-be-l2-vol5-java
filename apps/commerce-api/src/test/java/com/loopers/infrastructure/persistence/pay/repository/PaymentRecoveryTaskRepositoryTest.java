package com.loopers.infrastructure.persistence.pay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.pay.model.PaymentRecoveryTask;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class PaymentRecoveryTaskRepositoryTest {
    @Autowired
    PaymentRecoveryTaskRepository repository;
    @Autowired
    EntityManager entityManager;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("실행 권한은 한 명만 얻고 인계 후 옛 진행·회차 초기화·완료 상태 덮기를 차단한다")
    void conditionalOwnershipAndHandover() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String key = UUID.randomUUID().toString();
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        tx.executeWithoutResult(status -> repository.insert(PaymentRecoveryTask.create(key)));
        try {
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> { start.await(); return tx.execute(status -> repository.claim(key, firstId, now)); });
                var second = executor.submit(() -> { start.await(); return tx.execute(status -> repository.claim(key, secondId, now)); });
                start.countDown();
                assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            }
            PaymentRecoveryTask running = tx.execute(status -> repository.findByKey(key).orElseThrow());
            String owner = running.getExecutionId();
            assertThat(tx.<Boolean>execute(status -> repository.consumeRound(key, owner, 0, now))).isTrue();
            String next = UUID.randomUUID().toString();
            assertThat(tx.<Boolean>execute(status -> repository.handover(key, PaymentRecoveryTask.State.RUNNING, owner, 1, next, now))).isTrue();
            assertThat(tx.<Boolean>execute(status -> repository.consumeRound(key, owner, 1, now))).isFalse();
            assertThat(tx.<Boolean>execute(status -> repository.recordFailure(key, owner, 1, PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE, now))).isFalse();
            assertThat(tx.<Boolean>execute(status -> repository.consumeRound(key, next, 1, now))).isTrue();
            assertThat(tx.<Boolean>execute(status -> repository.consumeRound(key, next, 2, now))).isTrue();
            assertThat(tx.<Boolean>execute(status -> repository.consumeRound(key, next, 3, now))).isTrue();
            assertThat(tx.<Boolean>execute(status -> repository.consumeRound(key, next, 4, now))).isFalse();
            assertThat(tx.<Boolean>execute(status -> repository.requireManual(key, next, 4, PaymentRecoveryTask.Reason.RETRY_EXHAUSTED, now))).isTrue();
            assertThat(tx.<Boolean>execute(status -> repository.markApplied(key, now))).isTrue();
            assertThat(tx.<Boolean>execute(status -> repository.handover(key, PaymentRecoveryTask.State.MANUAL_REQUIRED, next, 4,
                    UUID.randomUUID().toString(), now))).isFalse();
            assertThat(tx.<Boolean>execute(status -> repository.markApplied(key, now))).isFalse();
            PaymentRecoveryTask restored = tx.execute(status -> repository.findByKey(key).orElseThrow());
            assertThat(restored.getUsedRounds()).isEqualTo(4);
            assertThat(restored.getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
        } finally {
            tx.executeWithoutResult(status -> entityManager.createQuery("delete from PaymentRecoveryTaskJpaEntity t where t.attemptKey = :key")
                    .setParameter("key", key).executeUpdate());
        }
    }
}
