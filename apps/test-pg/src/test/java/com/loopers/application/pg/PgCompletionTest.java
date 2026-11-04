package com.loopers.application.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.pg.usecase.CompleteDuePgPaymentsUseCase;
import com.loopers.domain.pg.model.PgScenario;
import com.loopers.domain.pg.model.PgStatus;
import com.loopers.domain.pg.model.PgTransaction;
import com.loopers.domain.pg.repository.PgTransactionRepository;
import com.loopers.support.PgDatabaseTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class PgCompletionTest extends PgDatabaseTest {
    @Autowired
    CompleteDuePgPaymentsUseCase complete;
    @Autowired
    PgTransactionRepository repository;
    @Autowired
    EntityManager entityManager;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @Transactional
    @DisplayName("기한이 지난 거래만 예정 결과로 한 번 완료하고 PENDING과 미래 거래는 유지한다")
    void completesOnlyDueTransactions() {
        // arrange
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        PgTransaction success = create(PgScenario.Preset.NORMAL_SUCCESS, now.minusSeconds(1));
        PgTransaction failure = create(PgScenario.Preset.LOST_FAILURE, now.minusSeconds(2));
        PgTransaction pending = create(PgScenario.Preset.PENDING, now.minusSeconds(10));
        PgTransaction future = create(PgScenario.Preset.DELAYED_SUCCESS, now);
        List.of(success, failure, pending, future).forEach(repository::insert);
        entityManager.flush();

        // act
        List<PgTransaction> completed = complete.execute(now);

        // assert
        assertThat(completed).extracting(PgTransaction::transactionKey)
                .containsExactlyInAnyOrder(success.transactionKey(), failure.transactionKey());
        assertThat(repository.findByKey(success.transactionKey()).orElseThrow().status()).isEqualTo(PgStatus.SUCCESS);
        assertThat(repository.findByKey(failure.transactionKey()).orElseThrow().status()).isEqualTo(PgStatus.FAILED);
        assertThat(repository.findByKey(pending.transactionKey()).orElseThrow().status()).isEqualTo(PgStatus.PENDING);
        assertThat(repository.findByKey(future.transactionKey()).orElseThrow().status()).isEqualTo(PgStatus.PENDING);
        assertThat(complete.execute(now)).isEmpty();
    }

    @Test
    @DisplayName("두 실행이 같은 기한 거래를 찾아도 완료를 기록한 실행 하나만 결과를 반환한다")
    void completesOnceUnderConcurrentPolling() throws Exception {
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        PgTransaction transaction = create(PgScenario.Preset.NORMAL_SUCCESS, now.minusSeconds(1));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> repository.insert(transaction));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return complete.execute(now); });
            var second = executor.submit(() -> { start.await(); return complete.execute(now); });
            start.countDown();
            long completedCount = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                    .stream().flatMap(List::stream)
                    .filter(item -> item.transactionKey().equals(transaction.transactionKey())).count();
            assertThat(completedCount).isEqualTo(1);
        } finally {
            tx.executeWithoutResult(status -> entityManager.createQuery(
                    "delete from PgTransactionJpaEntity t where t.transactionKey = :key")
                    .setParameter("key", transaction.transactionKey()).executeUpdate());
        }
    }

    private PgTransaction create(PgScenario.Preset preset, Instant createdAt) {
        return PgTransaction.create("WORKER-ORDER", 7000, "http://127.0.0.1:8080/api/v1/payments/callback",
                PgScenario.resolve(preset, null), createdAt);
    }
}
