package com.loopers.application.brand;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.product.Price;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BrandRemovalRegistrationRaceTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final long LOCK_WAIT_SECONDS = 3;

    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long brandId;
    private Long existingProduct;

    @Autowired
    BrandRemovalRegistrationRaceTest(
        BrandFacade brandFacade,
        ProductFacade productFacade,
        PlatformTransactionManager transactionManager,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        existingProduct = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("BRAND-004 · 삭제가 먼저 브랜드를 잡으면, 등록은 브랜드 공유 잠금에서 기다렸다가 BRAND_NOT_AVAILABLE 로 거절된다")
    @Test
    void registrationWaitsForDeletionAndIsRejected() throws Exception {
        Race race = new Race(
            () -> {
                brandFacade.delete(brandId);
                return null;
            },
            () -> productFacade.register(brandId, "니트", Price.of(59_000)).getId()
        );

        race.run();

        assertThat(race.secondWaitedOnLock).as("등록이 삭제의 브랜드 잠금에서 실제로 기다렸다").isTrue();
        assertThat(race.secondFailure)
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.BRAND_NOT_AVAILABLE);
        assertThat(brandAlive()).isFalse();
        assertThat(productAlive(existingProduct)).isFalse();
        assertThat(aliveProductsOfBrand()).as("삭제된 브랜드 아래 살아 있는 상품").isZero();
    }

    @DisplayName("BRAND-004 · 등록이 먼저 브랜드를 잡으면, 삭제는 기다렸다가 방금 등록된 상품까지 함께 지운다")
    @Test
    void deletionWaitsForRegistrationAndRemovesTheNewProduct() throws Exception {
        Race race = new Race(
            () -> productFacade.register(brandId, "니트", Price.of(59_000)).getId(),
            () -> {
                brandFacade.delete(brandId);
                return null;
            }
        );

        race.run();

        assertThat(race.secondWaitedOnLock).as("삭제가 등록의 브랜드 공유 잠금에서 실제로 기다렸다").isTrue();
        assertThat(race.secondFailure).as("삭제는 실패하지 않는다").isNull();
        Long registered = (Long) race.first.get();
        assertThat(brandAlive()).isFalse();
        assertThat(productAlive(existingProduct)).isFalse();
        assertThat(productAlive(registered)).as("삭제가 기다리는 동안 커밋된 상품도 지워졌다").isFalse();
        assertThat(aliveProductsOfBrand()).as("삭제된 브랜드 아래 살아 있는 상품").isZero();
    }

    @DisplayName("BRAND-005 · 삭제가 먼저 브랜드를 잡으면, 수정은 브랜드 잠금에서 기다렸다가 BRAND_NOT_FOUND 로 거절되고 브랜드를 되살리지 않는다")
    @Test
    void updateWaitsForDeletionAndDoesNotRevive() throws Exception {
        Race race = new Race(
            () -> {
                brandFacade.delete(brandId);
                return null;
            },
            () -> brandFacade.update(brandId, "무신사 스탠다드", "패션 플랫폼")
        );

        race.run();

        assertThat(race.secondWaitedOnLock).as("수정이 삭제의 브랜드 잠금에서 실제로 기다렸다").isTrue();
        assertThat(brandAlive()).as("삭제된 브랜드가 수정으로 되살아나지 않는다").isFalse();
        assertThat(race.secondFailure)
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.BRAND_NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM brand WHERE id = ?", String.class, brandId))
            .isEqualTo("무신사");
        assertThat(aliveProductsOfBrand()).as("삭제된 브랜드 아래 살아 있는 상품").isZero();
    }

    private final class Race {

        private final Callable<Object> firstAction;
        private final Callable<Object> secondAction;
        private Future<Object> first;
        private Future<Object> second;
        private boolean secondWaitedOnLock;
        private Throwable secondFailure;

        private Race(Callable<Object> firstAction, Callable<Object> secondAction) {
            this.firstAction = firstAction;
            this.secondAction = secondAction;
        }

        private void run() throws Exception {
            CountDownLatch firstHolds = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                first = executor.submit(() -> transactionTemplate.execute(status -> {
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
                    .as("첫 요청이 잠금을 쥔 채 커밋 전에 멈춰 있다").isTrue();

                second = executor.submit(secondAction);
                secondWaitedOnLock = awaitLockWait();

                releaseFirst.countDown();
                first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try {
                    second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    secondFailure = e.getCause();
                }
            } finally {
                releaseFirst.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private boolean awaitLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_SECONDS);
        try (Connection root = DriverManager.getConnection(
                System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
                "root",
                System.getProperty("datasource.mysql-jpa.main.password"));
             PreparedStatement waits = root.prepareStatement(
                 "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                     + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                     + "WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'brand'")) {
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

    private boolean brandAlive() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT deleted_at IS NULL FROM brand WHERE id = ?", Boolean.class, brandId));
    }

    private boolean productAlive(Long productId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT deleted_at IS NULL FROM product WHERE id = ?", Boolean.class, productId));
    }

    private int aliveProductsOfBrand() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM product WHERE brand_id = ? AND deleted_at IS NULL", Integer.class, brandId);
        return count == null ? -1 : count;
    }
}
