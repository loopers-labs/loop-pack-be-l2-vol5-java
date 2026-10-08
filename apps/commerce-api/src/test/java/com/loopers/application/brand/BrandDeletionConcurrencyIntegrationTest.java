package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BrandDeletionConcurrencyIntegrationTest {

    private static final int WORKER_COUNT = 2;
    private static final long TIMEOUT_SECONDS = 10L;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void concurrentDeletionOfSameBrandCommitsOnlyOnce() throws Exception {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
        CountDownLatch workersReady = new CountDownLatch(WORKER_COUNT);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(WORKER_COUNT);

        try {
            List<Future<DeleteResult>> attempts = List.of(
                submitDelete(workers, brand.getId(), workersReady, startWorkers),
                submitDelete(workers, brand.getId(), workersReady, startWorkers)
            );

            assertThat(workersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            List<DeleteResult> results = List.of(
                attempts.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                attempts.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );

            assertThat(results).containsExactlyInAnyOrder(DeleteResult.COMMITTED, DeleteResult.NOT_FOUND);
            entityManager.clear();
            assertThat(brandRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNotNull();
            assertThat(productRepository.findById(product.getId()).orElseThrow().getDeletedAt()).isNotNull();
        } finally {
            startWorkers.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void conditionalUpdatesDoNotOverwriteRowsReadBeforeBrandDeletion() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Air Max", 100_000L);
        product.changeStockTo(5L);
        product = productRepository.save(product);
        Brand staleBrand = brandRepository.findActiveById(brand.getId()).orElseThrow();
        Product staleProduct = productRepository.findById(product.getId()).orElseThrow();

        brandFacade.delete(brand.getId());

        List<Integer> affectedRows = new TransactionTemplate(transactionManager).execute(status -> List.of(
            brandRepository.updateActiveName(staleBrand.getId(), "Updated after deletion", ZonedDateTime.now()),
            productRepository.updateActiveDetails(
                staleProduct.getId(), "Updated after deletion", 200_000L, ZonedDateTime.now()
            ),
            productRepository.updateActiveStock(staleProduct.getId(), 99L, ZonedDateTime.now())
        ));

        entityManager.clear();
        Brand savedBrand = brandRepository.findById(brand.getId()).orElseThrow();
        Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(affectedRows).containsExactly(0, 0, 0);
        assertThat(savedBrand.getName()).isEqualTo("Nike");
        assertThat(savedBrand.getDeletedAt()).isNotNull();
        assertThat(savedProduct.getName()).isEqualTo("Air Max");
        assertThat(savedProduct.getPrice()).isEqualTo(100_000L);
        assertThat(savedProduct.getStock().amount()).isEqualTo(5L);
        assertThat(savedProduct.getDeletedAt()).isNotNull();
    }

    private Future<DeleteResult> submitDelete(
        ExecutorService workers,
        Long brandId,
        CountDownLatch workersReady,
        CountDownLatch startWorkers
    ) {
        return workers.submit(() -> {
            workersReady.countDown();
            if (!startWorkers.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to start concurrent brand deletions");
            }
            try {
                brandFacade.delete(brandId);
                return DeleteResult.COMMITTED;
            } catch (CoreException exception) {
                if (exception.getErrorType() == ErrorType.NOT_FOUND) {
                    return DeleteResult.NOT_FOUND;
                }
                throw exception;
            }
        });
    }

    private enum DeleteResult {
        COMMITTED,
        NOT_FOUND
    }
}
