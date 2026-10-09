package com.loopers.application.brand;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BrandConcurrencyTest {
    @Autowired private BrandFacade brandFacade;
    @Autowired private ProductFacade productFacade;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void clean() {
        cleanUp.truncateAllTables();
    }

    @Test
    void registrationKeepsSharedBrandLockUntilCommit() {
        long brandId = brand();
        transaction.executeWithoutResult(status -> {
            productFacade.create(brandId, "product", 1_000L, 5);
            // 서비스 내부를 멈추지 않는다. 반환 후 바깥 트랜잭션이 보유한 잠금을 다른 연결로 검사한다.
            assertThatCode(() -> probeLock(brandId, "for share nowait")).doesNotThrowAnyException();
            assertLockUnavailable(brandId, "for update nowait");
        });
        assertThatCode(() -> probeLock(brandId, "for update nowait")).doesNotThrowAnyException();
    }

    @Test
    void deletionKeepsExclusiveBrandLockAndLaterRegistrationIsRejected() {
        long brandId = brand();
        transaction.executeWithoutResult(status -> {
            brandFacade.delete(brandId);
            assertLockUnavailable(brandId, "for share nowait");
            assertLockUnavailable(brandId, "for update nowait");
        });

        assertThatThrownBy(() -> productFacade.create(brandId, "late", 1_000L, 5))
            .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
        assertThat(products.findAll()).isEmpty();
        assertThatCode(() -> probeLock(brandId, "for update nowait")).doesNotThrowAnyException();
    }

    @Test
    void rolledBackDeletionReleasesBrandForRegistration() {
        long brandId = brand();
        transaction.executeWithoutResult(status -> {
            brandFacade.delete(brandId);
            assertLockUnavailable(brandId, "for share nowait");
            status.setRollbackOnly();
        });

        productFacade.create(brandId, "after rollback", 1_000L, 5);

        assertThat(brands.findById(brandId).orElseThrow().getDeletedAt()).isNull();
        assertThat(products.findAll()).singleElement().satisfies(product -> {
            assertThat(product.getBrandId()).isEqualTo(brandId);
            assertThat(product.getDeletedAt()).isNull();
        });
    }

    @Test
    void concurrentRegistrationAndDeletionLeaveNoActiveProducts() throws Exception {
        long brandId = brand();
        List<Callable<Long>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int number = i;
            requests.add(() -> {
                try {
                    return productFacade.create(brandId, "product " + number, 1_000L, 5).id();
                } catch (CoreException exception) {
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                    return null;
                }
            });
        }
        requests.add(() -> {
            brandFacade.delete(brandId);
            return null;
        });

        List<Long> results = simultaneously(requests);

        assertThat(brands.findById(brandId).orElseThrow().getDeletedAt()).isNotNull();
        var stored = products.findAll();
        assertThat(stored).hasSize((int) results.stream().filter(id -> id != null).count());
        assertThat(stored).allSatisfy(product -> assertThat(product.getDeletedAt()).isNotNull());
    }

    @Test
    void differentProductsCanBeRegisteredUnderTheSameBrand() throws Exception {
        long brandId = brand();

        List<Long> ids = simultaneously(List.of(
            () -> productFacade.create(brandId, "first", 1_000L, 5).id(),
            () -> productFacade.create(brandId, "second", 2_000L, 3).id()));

        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(products.findAll()).hasSize(2).allSatisfy(product -> {
            assertThat(product.getBrandId()).isEqualTo(brandId);
            assertThat(product.getDeletedAt()).isNull();
        });
    }

    @Test
    void brandEditCannotRestoreADeletedBrand() throws Exception {
        long brandId = brand();

        simultaneously(List.of(
            () -> {
                try {
                    brandFacade.update(brandId, "renamed", "description");
                } catch (CoreException exception) {
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                }
                return true;
            },
            () -> {
                brandFacade.delete(brandId);
                return true;
            }));

        assertThat(brands.findById(brandId).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void waitingRegistrationReadsDeletionAfterCommit() throws Exception {
        long brandId = brand();
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try (var deleting = dataSource.getConnection()) {
            deleting.setAutoCommit(false);
            try {
                // 미커밋 삭제를 DB fixture로 준비한다. 실제 등록 서비스에는 대기 장치를 넣지 않는다.
                try (var query = deleting.prepareStatement("update brand set deleted_at = now() where id = ?")) {
                    query.setLong(1, brandId);
                    query.executeUpdate();
                }
                Future<ErrorType> registration = executor.submit(() -> {
                    started.countDown();
                    try {
                        productFacade.create(brandId, "waiting", 1_000L, 5);
                        return null;
                    } catch (CoreException exception) {
                        return exception.getErrorType();
                    }
                });
                await(started);
                assertThatThrownBy(() -> registration.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
                deleting.commit();
                assertThat(registration.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorType.NOT_FOUND);
            } finally {
                deleting.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(products.findAll()).isEmpty();
    }

    @Test
    void deletionIncludesProductWhenRegistrationTransactionCommitsFirst() throws Exception {
        long brandId = brand();
        var executor = Executors.newSingleThreadExecutor();
        List<Future<?>> pending = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        try {
            transaction.executeWithoutResult(status -> {
                productFacade.create(brandId, "first", 1_000L, 5);
                // 서비스 반환 후 커밋 전의 잠금 유지 여부를 검사한다. 서비스 내부에는 장벽을 넣지 않는다.
                Future<?> deletion = executor.submit(() -> {
                    started.countDown();
                    brandFacade.delete(brandId);
                });
                pending.add(deletion);
                await(started);
                assertThatThrownBy(() -> deletion.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            });
            pending.getFirst().get(10, TimeUnit.SECONDS);
        } finally {
            pending.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(brands.findById(brandId).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(products.findAll()).singleElement().satisfies(product -> {
            assertThat(product.getBrandId()).isEqualTo(brandId);
            assertThat(product.getDeletedAt()).isNotNull();
        });
    }

    @Test
    void deletingOneBrandDoesNotLockAnExistingProductOfAnotherBrand() {
        long deletingBrand = brand();
        long otherBrand = brands.save(new BrandModel("other brand", null)).getId();
        products.save(new ProductModel(deletingBrand, "deleted", 1_000L, 5));
        long otherProduct = products.save(new ProductModel(otherBrand, "kept", 1_000L, 5)).getId();

        System.out.println("brand candidate plan: " + jdbc.queryForList(
            "explain select id from product where brand_id = ? and deleted_at is null order by id", deletingBrand));
        System.out.println("product lock plan: " + jdbc.queryForList(
            "explain select * from product where id = ? for update", otherProduct));
        transaction.executeWithoutResult(status -> {
            brandFacade.delete(deletingBrand);
            assertThatCode(() -> {
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var query = connection.prepareStatement(
                        "select id from product where id = ? for update nowait")) {
                        query.setLong(1, otherProduct);
                        try (var result = query.executeQuery()) {
                            assertThat(result.next()).isTrue();
                        }
                    } finally {
                        connection.rollback();
                    }
                }
            }).doesNotThrowAnyException();
        });
        assertThat(products.findById(otherProduct).orElseThrow().getDeletedAt()).isNull();
        assertThat(products.findById(otherProduct).orElseThrow().getStockQuantity()).isEqualTo(5);
    }

    private long brand() {
        return brands.save(new BrandModel("brand", null)).getId();
    }

    private void assertLockUnavailable(long brandId, String clause) {
        assertThatThrownBy(() -> probeLock(brandId, clause)).isInstanceOf(SQLException.class)
            .satisfies(exception -> assertThat(((SQLException) exception).getErrorCode()).isEqualTo(3572));
    }

    private void probeLock(long brandId, String clause) throws SQLException {
        // NOWAIT는 테스트에서만 사용한다. 잠금 충돌 외의 SQL 오류를 성공으로 세지 않는다.
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("select id from brand where id = ? " + clause)) {
                query.setLong(1, brandId);
                query.setQueryTimeout(5);
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static <T> List<T> simultaneously(List<Callable<T>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return task.call();
                }));
            }
            await(ready);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test coordination timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }
}
