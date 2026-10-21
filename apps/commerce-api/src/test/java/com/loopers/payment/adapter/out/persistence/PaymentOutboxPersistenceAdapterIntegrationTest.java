package com.loopers.payment.adapter.out.persistence;

import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.domain.PaymentOutbox;
import com.loopers.payment.domain.PaymentOutboxStatus;
import com.loopers.support.concurrency.ConcurrentRunner;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발송 장부 (ADR-W5-01·03). claim과 close는 같은 PENDING 행을 조건부 UPDATE로 다투고, 이긴 쪽 하나만 true다.
 * 동시 실행은 스레드마다 별도 트랜잭션이어야 하므로 테스트 트랜잭션을 쓰지 않는다.
 */
@SpringBootTest
class PaymentOutboxPersistenceAdapterIntegrationTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 21, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

    @Autowired
    private PaymentOutboxPort outboxPort;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private PaymentOutbox reload(Long paymentId) {
        entityManager.clear();
        return entityManager.createQuery("SELECT o FROM PaymentOutbox o WHERE o.paymentId = :paymentId", PaymentOutbox.class)
            .setParameter("paymentId", paymentId)
            .getSingleResult();
    }

    @DisplayName("ADR-W5-01 결제 요청 장부는 PENDING·PAYMENT_REQUESTED로 저장된다.")
    @Test
    void savesPending() {
        // act
        outboxPort.save(PaymentOutbox.paymentRequested(1L));

        // assert
        PaymentOutbox reloaded = reload(1L);
        assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(reloaded.getEventType()).isEqualTo("PAYMENT_REQUESTED");
        assertThat(reloaded.getClaimedAt()).isNull();
    }

    @DisplayName("ADR-W5-01 한 결제에 장부는 한 줄이다 (payment_id unique).")
    @Test
    void paymentIdIsUnique() {
        // arrange
        outboxPort.save(PaymentOutbox.paymentRequested(1L));

        // act & assert
        assertThatThrownBy(() -> outboxPort.save(PaymentOutbox.paymentRequested(1L)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("ADR-W5-01 claim은 PENDING을 CLAIMED로 바꿀 때만 true이고, 두 번째 claim·뒤늦은 close는 false다.")
    @Test
    void claimsOnce() {
        // arrange
        outboxPort.save(PaymentOutbox.paymentRequested(1L));

        // act
        boolean first = outboxPort.claim(1L, NOW);
        boolean second = outboxPort.claim(1L, NOW.plusSeconds(1));
        boolean close = outboxPort.close(1L, NOW.plusSeconds(2));

        // assert
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(close).isFalse();
        PaymentOutbox reloaded = reload(1L);
        assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.CLAIMED);
        assertThat(reloaded.getClaimedAt()).isEqualTo(NOW);
    }

    @DisplayName("ADR-W5-03 close는 PENDING을 CLOSED로 바꾸고, 그 뒤 claim은 false다 (보내지 않는다).")
    @Test
    void closeBlocksClaim() {
        // arrange
        outboxPort.save(PaymentOutbox.paymentRequested(1L));

        // act
        boolean close = outboxPort.close(1L, NOW);
        boolean claim = outboxPort.claim(1L, NOW.plusSeconds(1));

        // assert
        assertThat(close).isTrue();
        assertThat(claim).isFalse();
        PaymentOutbox reloaded = reload(1L);
        assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.CLOSED);
        assertThat(reloaded.getCompletedAt()).isEqualTo(NOW);
        assertThat(reloaded.getClaimedAt()).isNull();
    }

    @DisplayName("ADR-W5-01 markDone은 CLAIMED만 DONE으로 바꾸고, PENDING은 그대로 둔다.")
    @Test
    void marksDoneOnlyClaimed() {
        // arrange
        outboxPort.save(PaymentOutbox.paymentRequested(1L));
        outboxPort.save(PaymentOutbox.paymentRequested(2L));
        outboxPort.claim(1L, NOW);

        // act
        outboxPort.markDone(1L, NOW.plusSeconds(3));
        outboxPort.markDone(2L, NOW.plusSeconds(3));

        // assert
        PaymentOutbox done = reload(1L);
        assertThat(done.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
        assertThat(done.getCompletedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(reload(2L).getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
    }

    @DisplayName("ADR-W5-01 같은 장부를 10스레드가 동시에 claim하면 정확히 1건만 true다.")
    @Test
    void concurrentClaimHasOneWinner() throws InterruptedException {
        // arrange
        outboxPort.save(PaymentOutbox.paymentRequested(1L));
        AtomicInteger wins = new AtomicInteger();
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> outboxPort.claim(1L, NOW) && wins.incrementAndGet() > 0);
        }

        // act
        List<ConcurrentRunner.Outcome> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertThat(ConcurrentRunner.count(outcomes, ConcurrentRunner.Kind.SUCCESS)).isEqualTo(10);
        assertThat(wins.get()).isEqualTo(1);
        assertThat(reload(1L).getStatus()).isEqualTo(PaymentOutboxStatus.CLAIMED);
    }

    @DisplayName("ADR-W5-03 claim 5스레드와 close 5스레드가 동시에 다투면 둘 중 정확히 하나만 이기고, 최종 상태는 이긴 쪽이다.")
    @Test
    void claimAndCloseRaceHasOneWinner() throws InterruptedException {
        // arrange
        outboxPort.save(PaymentOutbox.paymentRequested(1L));
        AtomicInteger claimWins = new AtomicInteger();
        AtomicInteger closeWins = new AtomicInteger();
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> outboxPort.claim(1L, NOW) && claimWins.incrementAndGet() > 0);
            tasks.add(() -> outboxPort.close(1L, NOW) && closeWins.incrementAndGet() > 0);
        }

        // act
        List<ConcurrentRunner.Outcome> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertThat(ConcurrentRunner.count(outcomes, ConcurrentRunner.Kind.SUCCESS)).isEqualTo(10);
        assertThat(claimWins.get() + closeWins.get()).isEqualTo(1);
        PaymentOutboxStatus expected = claimWins.get() == 1 ? PaymentOutboxStatus.CLAIMED : PaymentOutboxStatus.CLOSED;
        assertThat(reload(1L).getStatus()).isEqualTo(expected);
    }

    @DisplayName("plan.md 5-1 정리 대상 ①은 기준보다 먼저 저장된 PENDING 장부만, 오래된 순·limit만큼이다.")
    @Test
    void findsPendingCreatedBefore() {
        // arrange
        outboxPort.save(PaymentOutbox.paymentRequested(11L));
        outboxPort.save(PaymentOutbox.paymentRequested(12L));
        outboxPort.save(PaymentOutbox.paymentRequested(13L));
        outboxPort.claim(13L, NOW);
        ZonedDateTime afterSaving = ZonedDateTime.now().plusMinutes(1);
        ZonedDateTime beforeSaving = ZonedDateTime.now().minusMinutes(1);

        // act & assert
        assertThat(outboxPort.findPendingCreatedBefore(afterSaving, 10)).containsExactly(11L, 12L);
        assertThat(outboxPort.findPendingCreatedBefore(afterSaving, 1)).containsExactly(11L);
        assertThat(outboxPort.findPendingCreatedBefore(beforeSaving, 10)).isEmpty();
    }

    @DisplayName("plan.md 5-1 정리 대상 ②는 기준보다 먼저 claim했는데 아직 CLAIMED인 장부만, 오래된 순이다.")
    @Test
    void findsClaimedBefore() {
        // arrange
        for (long paymentId = 21L; paymentId <= 25L; paymentId++) {
            outboxPort.save(PaymentOutbox.paymentRequested(paymentId));
        }
        outboxPort.claim(21L, NOW.minusMinutes(2));   // 오래된 claim — 포함 (두 번째)
        outboxPort.claim(22L, NOW.minusMinutes(5));   // 더 오래된 claim — 포함 (첫 번째)
        outboxPort.claim(23L, NOW);                   // 방금 claim — 제외
        outboxPort.claim(24L, NOW.minusMinutes(5));
        outboxPort.markDone(24L, NOW);                // DONE — 제외
        // 25L은 PENDING — 제외

        // act
        List<Long> found = outboxPort.findClaimedBefore(NOW.minusMinutes(1), 10);

        // assert
        assertThat(found).containsExactly(22L, 21L);
        assertThat(outboxPort.findClaimedBefore(NOW.minusMinutes(1), 1)).containsExactly(22L);
    }
}
