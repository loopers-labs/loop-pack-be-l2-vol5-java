package com.loopers.application.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Price;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ChargeAndPayRaceTest {

    private static final long BUYER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final long TIMEOUT_SECONDS = 10;

    private final OrderFacade orderFacade;
    private final PointFacade pointFacade;
    private final ProductFacade productFacade;
    private final BrandFacade brandFacade;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long orderId;

    @Autowired
    ChargeAndPayRaceTest(
        OrderFacade orderFacade,
        PointFacade pointFacade,
        ProductFacade productFacade,
        BrandFacade brandFacade,
        PlatformTransactionManager transactionManager,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderFacade = orderFacade;
        this.pointFacade = pointFacade;
        this.productFacade = productFacade;
        this.brandFacade = brandFacade;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        Long productId = productFacade.register(brandId, "코트", Price.of(7_000)).getId();
        productFacade.adjustStock(productId, Quantity.of(100));
        pointFacade.charge(BUYER, ChargeAmount.of(10_000), NOW);
        orderId = orderFacade.place(new OrderCreateCommand(BUYER,
            List.of(new OrderCreateCommand.Line(productId, OrderQuantity.of(1)))), NOW).getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POINT · 확정이 포인트 행을 쥔 동안 들어온 2,000원 충전은 그 잠금에서 기다렸다가 반영된다: 둘 다 성공 · 최종 잔액 5,000원")
    @Test
    void chargeWaitsForConfirmation() throws Exception {
        Race race = new Race(
            () -> orderFacade.confirm(BUYER, orderId, NOW),
            () -> pointFacade.charge(BUYER, ChargeAmount.of(2_000), NOW)
        );

        race.run();

        assertThat(race.secondWaitedOnLock).as("충전이 확정의 포인트 잠금에서 실제로 기다렸다").isTrue();
        assertBothApplied();
    }

    @DisplayName("POINT · 충전이 포인트 행을 쥔 동안 들어온 7,000원 확정은 그 잠금에서 기다렸다가 충전 뒤 잔액에서 결제한다: 둘 다 성공 · 최종 잔액 5,000원")
    @Test
    void confirmationWaitsForCharge() throws Exception {
        Race race = new Race(
            () -> pointFacade.charge(BUYER, ChargeAmount.of(2_000), NOW),
            () -> orderFacade.confirm(BUYER, orderId, NOW)
        );

        race.run();

        assertThat(race.secondWaitedOnLock).as("확정이 충전의 포인트 잠금에서 실제로 기다렸다").isTrue();
        assertBothApplied();
    }

    private void assertBothApplied() {
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
            .isEqualTo("CONFIRMED");
        assertThat(balance()).isEqualTo(5_000L);
        assertThat(balance()).as("잔액 = 원장 합").isEqualTo(ledgerSum());
    }

    private long balance() {
        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM user_point WHERE user_id = ?", Long.class, BUYER);
        return balance == null ? -1 : balance;
    }

    private long ledgerSum() {
        Long sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN type = 'CHARGE' THEN amount ELSE -amount END), 0) "
                + "FROM point_transaction WHERE user_id = ?", Long.class, BUYER);
        return sum == null ? -1 : sum;
    }

    private boolean awaitLockWaitOnPoint() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        try (Connection root = DriverManager.getConnection(
                System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
                "root",
                System.getProperty("datasource.mysql-jpa.main.password"));
             PreparedStatement waits = root.prepareStatement(
                 "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                     + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                     + "WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'user_point'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = waits.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) > 0) {
                        return true;
                    }
                }
                Thread.onSpinWait();
            }
            return false;
        }
    }

    private final class Race {

        private final Callable<Object> firstAction;
        private final Callable<Object> secondAction;
        private boolean secondWaitedOnLock;

        private Race(Callable<Object> firstAction, Callable<Object> secondAction) {
            this.firstAction = firstAction;
            this.secondAction = secondAction;
        }

        private void run() throws Exception {
            CountDownLatch firstHolds = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Object> first = executor.submit(() -> transactionTemplate.execute(status -> {
                    try {
                        Object result = firstAction.call();
                        firstHolds.countDown();
                        if (!releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("붙들어 둔 트랜잭션을 놓아줄 신호가 오지 않았다");
                        }
                        return result;
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
                assertThat(firstHolds.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("첫 요청이 포인트 행을 쥔 채 커밋 전에 멈춰 있다").isTrue();

                Future<Object> second = executor.submit(secondAction);
                secondWaitedOnLock = awaitLockWaitOnPoint();

                releaseFirst.countDown();
                first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
                executor.shutdownNow();
            }
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("worker 가 정리된다").isTrue();
        }
    }
}
