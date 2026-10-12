package com.loopers.application.brand;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class BrandDeletionRegistrationLockIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10L;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandRepository brandRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private BrandLockTestSupport locks;

    @BeforeEach
    void setUp() {
        locks = new BrandLockTestSupport(dataSource, jdbcTemplate, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void deletionHoldsExclusiveBrandLockBeforeUpdatingProducts() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));

        doAnswer(invocation -> {
            locks.assertExclusiveLock(brand.getId());
            return invocation.callRealMethod();
        }).when(productRepository).softDeleteActiveByBrandId(eq(brand.getId()), any(ZonedDateTime.class));

        brandFacade.delete(brand.getId());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(brandRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNotNull();
            assertThat(productRepository.findById(product.getId()).orElseThrow().getDeletedAt()).isNotNull();
        });
    }

    @Test
    void registrationHoldsSharedBrandLockBeforeInsertingProduct() {
        Brand brand = brandRepository.save(Brand.create("Nike"));

        doAnswer(invocation -> {
            locks.assertSharedLock(brand.getId());
            return invocation.callRealMethod();
        }).when(productRepository).save(any(Product.class));

        productFacade.register(brand.getId(), "Air Max", 100_000L);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            assertThat(productRepository.findAllActive()).singleElement().satisfies(product ->
                assertThat(product.getBrandId()).isEqualTo(brand.getId())
            )
        );
    }

    @Test
    void registrationWaitsForDeletionAndRejectsDeletedBrand() throws Exception {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        productRepository.save(Product.create(brand.getId(), "Existing", 100_000L));
        CountDownLatch deletionWritesCompleted = new CountDownLatch(1);
        CountDownLatch allowDeletionCommit = new CountDownLatch(1);
        CountDownLatch registrationStarted = new CountDownLatch(1);
        AtomicLong registrationConnectionId = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> deletion = locks.submit(workers, () -> {
                brandFacade.delete(brand.getId());
                deletionWritesCompleted.countDown();
                locks.awaitLatch(allowDeletionCommit);
            });
            assertThat(deletionWritesCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> registration = locks.submit(
                workers, registrationConnectionId, registrationStarted,
                () -> productFacade.register(brand.getId(), "New product", 120_000L)
            );
            assertThat(registrationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(registrationConnectionId.get(), "brands");

            allowDeletionCommit.countDown();
            deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThatThrownBy(() -> registration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND)
                );

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(brandRepository.findActiveById(brand.getId())).isEmpty();
                assertThat(productRepository.findAllActive()).isEmpty();
                assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM products WHERE brand_id = ?", Long.class, brand.getId()
                )).isEqualTo(1L);
            });
        } finally {
            allowDeletionCommit.countDown();
            locks.shutDown(workers);
        }
    }

    @Test
    void deletionWaitsForRegistrationAndIncludesNewProduct() throws Exception {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        CountDownLatch registrationWritesCompleted = new CountDownLatch(1);
        CountDownLatch allowRegistrationCommit = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        AtomicLong deletionConnectionId = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> registration = locks.submit(workers, () -> {
                productFacade.register(brand.getId(), "New product", 120_000L);
                registrationWritesCompleted.countDown();
                locks.awaitLatch(allowRegistrationCommit);
            });
            assertThat(registrationWritesCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> deletion = locks.submit(
                workers, deletionConnectionId, deletionStarted, () -> brandFacade.delete(brand.getId())
            );
            assertThat(deletionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(deletionConnectionId.get(), "brands");

            allowRegistrationCommit.countDown();
            registration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Brand deletedBrand = brandRepository.findById(brand.getId()).orElseThrow();
                assertThat(deletedBrand.getDeletedAt()).isNotNull();
                assertThat(productRepository.findAll()).singleElement().satisfies(product -> {
                    assertThat(product.getBrandId()).isEqualTo(brand.getId());
                    assertThat(product.getDeletedAt()).isEqualTo(deletedBrand.getDeletedAt());
                });
            });
        } finally {
            allowRegistrationCommit.countDown();
            locks.shutDown(workers);
        }
    }

}
