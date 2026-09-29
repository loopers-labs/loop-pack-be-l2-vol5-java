package com.loopers.infrastructure.point;

import com.loopers.application.point.PointFacade;
import com.loopers.domain.common.Money;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.point.PointService;
import com.loopers.domain.point.PointTransaction;
import com.loopers.domain.point.UserPointRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserPointRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");

    private final PointService pointService;

    private final PointFacade pointFacade;
    private final UserPointRepository userPointRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    UserPointRepositoryIntegrationTest(
        PointService pointService,
        PointFacade pointFacade,
        UserPointRepository userPointRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.pointService = pointService;
        this.pointFacade = pointFacade;
        this.userPointRepository = userPointRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("충전한 잔액이 저장되고, 다시 읽어도 같은 값이다.")
    @Test
    void persistsBalanceAcrossReads() {
        pointFacade.charge(1L, ChargeAmount.of(10_000), NOW);

        assertThat(pointService.getBalance(1L)).isEqualTo(Money.of(10_000));
        assertThat(userPointRepository.findByUserId(1L)).isPresent();
    }

    @DisplayName("유저별 잔액은 서로 섞이지 않는다.")
    @Test
    void keepsBalancesIsolatedPerUser() {
        pointFacade.charge(1L, ChargeAmount.of(100), NOW);
        pointFacade.charge(2L, ChargeAmount.of(200), NOW);

        assertThat(pointService.getBalance(1L)).isEqualTo(Money.of(100));
        assertThat(pointService.getBalance(2L)).isEqualTo(Money.of(200));
    }

    @DisplayName("잔액은 원장에 쌓인 금액의 합과 같고, 마지막 줄의 balanceAfter 와도 같다.")
    @Test
    void keepsBalanceConsistentWithLedger() {
        pointFacade.charge(1L, ChargeAmount.of(1_000), NOW);
        pointFacade.charge(1L, ChargeAmount.of(2_000), NOW);
        pointFacade.charge(1L, ChargeAmount.of(500), NOW);

        List<PointTransaction> ledger = userPointRepository.findTransactions(1L);
        long sum = ledger.stream().mapToLong(transaction -> transaction.amount().amount()).sum();

        assertThat(ledger).hasSize(3);
        assertThat(pointService.getBalance(1L).amount()).isEqualTo(sum);
        assertThat(ledger.get(ledger.size() - 1).balanceAfter()).isEqualTo(pointService.getBalance(1L));
    }

    @DisplayName("Q-5 · 포인트 행이 없는 유저에게 첫 충전이 동시에 들어와도 둘 다 성공하고 합산된다.")
    @Test
    void doesNotRaceOnFirstCharge() throws InterruptedException {
        long userId = 1L;
        long amountEach = 10_000L;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    pointFacade.charge(userId, ChargeAmount.of(amountEach), NOW);
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(failures)
            .as("둘 다 성공해야 한다. 실패한 쪽은 %s", failures.stream().map(Throwable::toString).toList())
            .isEmpty();

        List<PointTransaction> ledger = userPointRepository.findTransactions(userId);
        long sum = ledger.stream().mapToLong(transaction -> transaction.amount().amount()).sum();

        assertThat(userPointRepository.findByUserId(userId)).isPresent();
        assertThat(ledger).hasSize(2);
        assertThat(pointService.getBalance(userId).amount()).isEqualTo(amountEach * 2);
        assertThat(sum).isEqualTo(amountEach * 2);
        assertThat(ledger.get(ledger.size() - 1).balanceAfter()).isEqualTo(pointService.getBalance(userId));
    }

    @DisplayName("같은 유저에게 동시에 충전이 들어와도 잔액이 유실되지 않는다.")
    @Test
    void doesNotLoseConcurrentCharges() throws InterruptedException {
        long userId = 1L;
        int threads = 20;
        long amountEach = 100L;
        pointFacade.charge(userId, ChargeAmount.of(amountEach), NOW);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    pointFacade.charge(userId, ChargeAmount.of(amountEach), NOW);
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long expected = amountEach * (succeeded.get() + 1);
        assertThat(succeeded.get()).isEqualTo(threads);
        assertThat(pointService.getBalance(userId).amount()).isEqualTo(expected);
        assertThat(userPointRepository.findTransactions(userId)).hasSize(succeeded.get() + 1);
    }
}
