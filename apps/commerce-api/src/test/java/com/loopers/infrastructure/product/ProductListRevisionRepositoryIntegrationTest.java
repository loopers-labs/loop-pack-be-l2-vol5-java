package com.loopers.infrastructure.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevision;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ProductListRevisionRepositoryIntegrationTest.SqlCaptureConfiguration.class)
class ProductListRevisionRepositoryIntegrationTest {

    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private SqlCapture sqlCapture;

    private boolean workersTerminated = true;

    @AfterEach
    void tearDown() {
        assertThat(workersTerminated).as("worker 종료 후에만 DB를 정리한다").isTrue();
        cleanup.truncateAllTables();
    }

    @Test
    void treatsAnAbsentRowAsZeroAndAtomicallyInitializesAndAdvancesIt() {
        assertThat(revisions.current()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_list_revision", Long.class)).isZero();

        transaction().executeWithoutResult(status -> revisions.advance());
        assertThat(revisions.current()).isEqualTo(1);
        transaction().executeWithoutResult(status -> {
            revisions.advance();
            revisions.advance();
        });

        assertThat(revisions.current()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_list_revision", Long.class)).isEqualTo(1);
    }

    @Test
    void requiresTheCallersTransactionForEveryAdvance() {
        assertThatThrownBy(revisions::advance).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(revisions.current()).isZero();
    }

    @Test
    void flushesBusinessStateAndRollsBackBothTheBusinessChangeAndTheRevision() {
        Product product = product();
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            products.lockById(product.getId()).orElseThrow().changeStockQuantityTo(2);
            revisions.advance();
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class,
                product.getId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id = 1", Long.class))
                .isEqualTo(1);
            throw new IllegalStateException("rollback after actual SQL");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback after actual SQL");

        assertThat(products.findById(product.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(revisions.current()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_list_revision", Long.class)).isZero();
    }

    @Test
    void failureToAdvanceRollsBackAlreadyFlushedBusinessSql() {
        Product product = product();
        jdbc.update("INSERT INTO product_list_revision (id, revision) VALUES (1, ?)", Long.MAX_VALUE);

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            products.lockById(product.getId()).orElseThrow().changeStockQuantityTo(2);
            revisions.advance();
        })).isInstanceOf(RuntimeException.class);

        assertThat(products.findById(product.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(revisions.current()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void readsTheScalarRevisionRatherThanAStaleManagedEntity() {
        jdbc.update("INSERT INTO product_list_revision (id, revision) VALUES (1, 0)");
        transaction().executeWithoutResult(status -> {
            ProductListRevision managed = entityManager.find(ProductListRevision.class, 1L);
            assertThat(managed.getRevision()).isZero();
            revisions.advance();

            assertThat(managed.getRevision()).as("native 증가로 JPA 1차 캐시 객체는 갱신되지 않는다").isZero();
            assertThat(revisions.current()).isEqualTo(1);
        });
    }

    @Test
    void flushesTheBusinessUpdateBeforeAcquiringTheSharedRevisionWriteLock() {
        Product product = product();
        transaction().executeWithoutResult(status -> {
            products.lockById(product.getId()).orElseThrow().changeStockQuantityTo(2);
            sqlCapture.statements.clear();

            revisions.advance();

            List<String> writes = sqlCapture.statements.stream()
                .map(sql -> sql.strip().toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("update product ") || sql.startsWith("insert into product_list_revision "))
                .toList();
            assertThat(writes).hasSize(2);
            assertThat(writes.getFirst()).startsWith("update product ");
            assertThat(writes.getLast()).startsWith("insert into product_list_revision ")
                .contains("on duplicate key update revision = revision + 1");
            // Inspector의 SQL 준비 순서와 별개로 같은 연결에서 실제 실행 결과도 확인한다.
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class,
                product.getId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id = 1", Long.class))
                .isEqualTo(1);
        });
        assertThat(products.findById(product.getId()).orElseThrow().getStockQuantity()).isEqualTo(2);
        assertThat(revisions.current()).isEqualTo(1);
    }

    @Test
    void keepsRevisionAndProductInTheSameRepeatableReadSnapshot() throws Exception {
        Product product = product();
        transaction().executeWithoutResult(status -> revisions.advance());
        var worker = Executors.newSingleThreadExecutor();
        workersTerminated = false;
        try {
            TransactionTemplate reader = transaction();
            reader.setReadOnly(true);
            reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            reader.executeWithoutResult(status -> {
                long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                assertThat(revisions.current()).isEqualTo(1);
                Future<?> write = worker.submit(() -> transaction().executeWithoutResult(writeStatus -> {
                    products.lockById(product.getId()).orElseThrow().changeStockQuantityTo(2);
                    revisions.advance();
                }));
                await(write);

                assertThat(revisions.current()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class,
                    product.getId())).isEqualTo(5);
                assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connection);
            });
            assertThat(revisions.current()).isEqualTo(2);
            assertThat(products.findById(product.getId()).orElseThrow().getStockQuantity()).isEqualTo(2);
        } finally {
            worker.shutdownNow();
            workersTerminated = worker.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentTransactionsNeverLoseAnAdvanceAndDoNotCountRollbacks() throws Exception {
        // 운영 008 DDL은 트래픽 시작 전에 singleton 0을 만든다. 동시 최초 INSERT/rollback과 구분한다.
        jdbc.update("INSERT INTO product_list_revision (id, revision) VALUES (1, 0)");
        int workers = 8;
        var executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        workersTerminated = false;
        try {
            for (int index = 0; index < workers; index++) {
                boolean rollback = index < 2;
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    transaction().executeWithoutResult(status -> {
                        revisions.advance();
                        if (rollback) {
                            status.setRollbackOnly();
                        }
                    });
                    return !rollback;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> committed = new ArrayList<>();
            for (Future<Boolean> outcome : outcomes) {
                committed.add(outcome.get(20, TimeUnit.SECONDS));
            }
            assertThat(committed.stream().filter(Boolean::booleanValue)).hasSize(6);
            assertThat(revisions.current()).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_list_revision", Long.class)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Product product() {
        Brand brand = brands.save(new Brand("버전 원자성 브랜드"));
        return products.save(new Product(brand, "버전 원자성 상품", 1000, 5));
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(15);
        return template;
    }

    private static void await(Future<?> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("snapshot writer did not complete", failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {
        @Bean
        SqlCapture revisionSqlCapture() {
            return new SqlCapture();
        }

        @Bean
        HibernatePropertiesCustomizer captureRevisionSql(SqlCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }
    }

    static class SqlCapture implements StatementInspector {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            statements.add(sql);
            return sql;
        }
    }
}
