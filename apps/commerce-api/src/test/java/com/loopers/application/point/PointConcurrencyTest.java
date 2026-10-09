package com.loopers.application.point;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PointConcurrencyTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private PointFacade points;
    @Autowired private PointExpirationWorker expiration;
    @Autowired private OrderFacade orders;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private PointBalanceJpaRepository balances;
    @Autowired private UserJpaRepository users;
    @Autowired private TransactionTemplate transaction;
    @Autowired private EntityManager em;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void clean() {
        cleanUp.truncateAllTables();
    }

    @Test
    void differentProductsStillShareOnePointBalance() throws Exception {
        long userId = userWith(10_000);
        List<Callable<String>> requests = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long orderId = draft(userId, 4_000);
            requests.add(() -> confirm(userId, orderId));
        }
        assertThat(together(requests)).containsExactlyInAnyOrder("success", "success", "insufficient");
        verify(userId, 2_160, 8_000, 0);
        assertThat(scalar("select count(*) from orders where status = 'CONFIRMED'")).isEqualTo(2);
        assertThat(scalar("select count(*) from orders where status = 'DRAFT' and paid_amount = 0")).isEqualTo(1);
        assertThat(scalar("select sum(quantity) from product")).isEqualTo(28);
    }

    @Test
    void chargeAndPaymentBothRemainInLedger() throws Exception {
        long userId = userWith(10_000);
        long orderId = draft(userId, 7_000);
        together(List.of(() -> { points.charge(userId, 2_000); return "charge"; },
            () -> confirm(userId, orderId)));
        verify(userId, 5_140, 7_000, 0);
    }

    @Test
    void expirationAndSixHundredPaymentSucceedInEitherOrder() throws Exception {
        long userId = expiredFixture();
        long orderId = draft(userId, 600);
        assertThat(together(List.of(() -> { expiration.expire(userId, ZonedDateTime.now()); return "expire"; },
            () -> confirm(userId, orderId)))).containsExactlyInAnyOrder("expire", "success");
        verify(userId, 112, 600, 300);
    }

    @Test
    void expirationAndEightHundredPaymentNeverSpendExpiredPoints() throws Exception {
        long userId = expiredFixture();
        long orderId = draft(userId, 800);
        assertThat(together(List.of(() -> { expiration.expire(userId, ZonedDateTime.now()); return "expire"; },
            () -> confirm(userId, orderId)))).containsExactlyInAnyOrder("expire", "insufficient");
        verify(userId, 700, 0, 300);
        assertThat(scalar("select sum(quantity) from product")).isEqualTo(10);
        assertThat(scalar("select count(*) from orders where status = 'DRAFT' and paid_amount = 0")).isEqualTo(1);
    }

    @Test
    void paymentChargeLookupAndBatchExpireTheSameGrantOnlyOnce() throws Exception {
        long userId = expiredFixture();
        long orderId = draft(userId, 600);
        together(List.of(() -> { points.charge(userId, 2_000); return "charge"; },
            () -> confirm(userId, orderId),
            () -> { points.getBalance(userId); return "lookup"; },
            () -> { expiration.expire(userId, ZonedDateTime.now()); return "expire"; }));
        verify(userId, 2_112, 600, 300);
        assertThat(scalar("select count(*) from point_usage where type = 'EXPIRATION'")).isEqualTo(1);
    }

    @Test
    void readsCurrentGrantsEvenWhenTransactionAlreadyHasAnOldSnapshot() throws Exception {
        long userId = userWith(10_000);
        long orderId = draft(userId, 7_000);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch chargeCommitted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> reader = executor.submit(() -> transaction.executeWithoutResult(status -> {
                users.findById(userId).orElseThrow();
                snapshotRead.countDown();
                await(chargeCommitted);
                points.charge(userId, 1_000);
            }));
            await(snapshotRead);
            points.charge(userId, 2_000);
            orders.confirm(userId, orderId);
            chargeCommitted.countDown();
            reader.get(30, TimeUnit.SECONDS);
            verify(userId, 6_140, 7_000, 0);
        } finally {
            chargeCommitted.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
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

    private long expiredFixture() {
        long id = userWith(700);
        transaction.executeWithoutResult(status -> {
            balances.findByUserId(id).orElseThrow().reward(300, ZonedDateTime.now().minusYears(1).minusDays(2));
        });
        return id;
    }

    private long draft(long userId, long price) {
        var brand = brands.save(new BrandModel("brand-" + System.nanoTime(), null));
        var product = products.save(new ProductModel(brand.getId(), "product", price, 10));
        return orders.create(userId, List.of(new OrderFacade.OrderLine(product.getId(), 1))).id();
    }

    private String confirm(long userId, long orderId) {
        try {
            orders.confirm(userId, orderId);
            return "success";
        } catch (CoreException exception) {
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(exception.getMessage()).isEqualTo("포인트 잔액이 부족합니다.");
            return "insufficient";
        }
    }

    private void verify(long userId, long balance, long paid, long expired) {
        transaction.executeWithoutResult(status -> {
            em.clear();
            assertThat(balances.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(balance);
            assertThat(scalar("select coalesce(sum(amount),0) from point_usage where type = 'PAYMENT'"))
                .isEqualTo(paid);
            assertThat(scalar("select coalesce(sum(amount),0) from point_usage where type = 'EXPIRATION'"))
                .isEqualTo(expired);
            assertThat(scalar("select count(*) from (select g.id from point_grant g "
                + "left join point_usage u on u.point_grant_id = g.id group by g.id, g.amount "
                + "having coalesce(sum(u.amount),0) > g.amount) overused")).isZero();
            assertThat(scalar("select coalesce(sum(amount),0) from point_grant")
                - scalar("select coalesce(sum(amount),0) from point_usage")).isEqualTo(balance);
        });
    }

    private long scalar(String sql) {
        return transaction.execute(status -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    private <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> { await(start); return task.call(); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
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
