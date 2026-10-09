package com.loopers.product.application;

import com.loopers.brand.application.BrandUseCase;
import com.loopers.brand.domain.Brand;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.Stock;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
class BrandProductConcurrencyTest {

    @Autowired private ProductUseCase productUseCase;
    @Autowired private BrandUseCase brandUseCase;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        new CommerceFixture(entityManager, transactionManager).truncateRemainingTables();
    }

    @DisplayName("[R-ADMIN-05, R-ADMIN-16] 브랜드 삭제가 먼저 잠금을 잡으면 상품 생성은 기다린 뒤 거절하고 상품·재고를 남기지 않는다.")
    @Test
    void rejectsCreationAfterConcurrentBrandDeletion() throws Exception {
        Brand brand = new CommerceFixture(entityManager, transactionManager).brand("Nike");
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch creationStarted = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = workers.submit(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    brandUseCase.delete(brand.getId());
                    entityManager.flush();
                    deleted.countDown();
                    await(allowCommit);
                }));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ErrorCode> creation = workers.submit(() -> {
                creationStarted.countDown();
                try {
                    productUseCase.create(brand.getId(), "Air", 1_000L);
                    return null;
                } catch (CoreException exception) {
                    return exception.getErrorCode();
                }
            });
            assertThat(creationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> creation.get(300, TimeUnit.MILLISECONDS));
            allowCommit.countDown();
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(creation.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.BRAND_NOT_FOUND);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(entityManager.find(Brand.class, brand.getId()).isDeleted()).isTrue();
                assertThat(entityManager.createQuery("select p from Product p", Product.class).getResultList())
                    .isEmpty();
                assertThat(entityManager.createQuery("select s from Stock s", Stock.class).getResultList())
                    .isEmpty();
            });
        } finally {
            allowCommit.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("[R-ADMIN-16] 상품 생성이 먼저 잠금을 잡으면 브랜드 삭제는 생성 커밋 후 새 상품까지 삭제한다.")
    @Test
    void deletesProductCreatedBeforeBrandDeletion() throws Exception {
        Brand brand = new CommerceFixture(entityManager, transactionManager).brand("Nike");
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Product> creation = workers.submit(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    Product product = productUseCase.create(brand.getId(), "Air", 1_000L);
                    entityManager.flush();
                    created.countDown();
                    await(allowCommit);
                    return product;
                }));
            assertThat(created.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> deletion = workers.submit(() -> {
                deletionStarted.countDown();
                brandUseCase.delete(brand.getId());
            });
            assertThat(deletionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> deletion.get(300, TimeUnit.MILLISECONDS));
            allowCommit.countDown();
            Product product = creation.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(entityManager.find(Brand.class, brand.getId()).isDeleted()).isTrue();
                assertThat(entityManager.find(Product.class, product.getId()).isDeleted()).isTrue();
                assertThat(entityManager.createQuery("select s from Stock s", Stock.class).getResultList())
                    .singleElement().satisfies(stock -> {
                        assertThat(stock.getProductId()).isEqualTo(product.getId());
                        assertThat(stock.quantity()).isZero();
                    });
            });
        } finally {
            allowCommit.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to commit transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to commit transaction", exception);
        }
    }
}
