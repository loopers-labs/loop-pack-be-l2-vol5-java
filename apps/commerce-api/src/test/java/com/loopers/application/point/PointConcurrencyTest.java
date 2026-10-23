package com.loopers.application.point;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포인트 충전의 사용자별 잠금. 주문 결제가 PG로 바뀌어 주문 확정으로 포인트를 쓰는 장면은 더 이상 없다.
 */
@SpringBootTest
class PointConcurrencyTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private PointFacade points;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void clean() {
        cleanUp.truncateAllTables();
    }

    @Test
    void holdsSameUserLockUntilCommitWhileOtherUserCanCharge() throws Exception {
        long firstUser = userWith(1_000);
        long otherUser = userWith(1_000);
        var executor = Executors.newFixedThreadPool(2);
        List<Future<?>> waiting = new ArrayList<>();
        try {
            transaction.executeWithoutResult(status -> {
                points.charge(firstUser, 100);
                CountDownLatch started = new CountDownLatch(1);
                Future<?> sameUser = executor.submit(() -> {
                    started.countDown();
                    points.charge(firstUser, 200);
                });
                waiting.add(sameUser);
                await(started);
                assertThatThrownBy(() -> sameUser.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
                try {
                    assertThat(executor.submit(() -> points.charge(otherUser, 300).balance())
                        .get(10, TimeUnit.SECONDS)).isEqualTo(1_300);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            waiting.get(0).get(30, TimeUnit.SECONDS);
            assertThat(points.getBalance(firstUser).balance()).isEqualTo(1_300);
            assertThat(points.getBalance(otherUser).balance()).isEqualTo(1_300);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long userWith(long amount) {
        long id = registration.register().getId();
        points.charge(id, amount);
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test coordination timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
