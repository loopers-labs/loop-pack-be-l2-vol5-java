package com.loopers.application.brand;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test-only facade candidate; the production deletion remains unchanged. */
@SpringBootTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureMockMvc
@Import(BrandRcBulkValidationTest.CandidateConfig.class)
class BrandRcBulkValidationTest {
    @Autowired private BrandFacade deletion;
    @Autowired private RcBulkFacade candidate;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository productRows;
    @Autowired private ProductFacade products;
    @Autowired private OrderFacade orders;
    @Autowired private PointFacade points;
    @Autowired private UserRegistrationService users;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private MockMvc mvc;

    @AfterEach
    void clean() {
        candidate.setFailure(false);
        cleanUp.truncateAllTables();
    }

    @Test
    void newFacadeTransactionUsesCandidateIsolationAndPreservesExistingDeletionTimeAndValues() {
        Fixture f = fixture();
        long old = productRows.save(new ProductModel(f.brand(), "old", 1000, 0)).getId();
        jdbc.update("update product set deleted_at='2020-01-01', updated_at='2020-01-01' where id=?", old);
        deletion.delete(f.brand());
        assertThat(candidate.getObservedIsolation()).isEqualTo(candidate.getExpectedIsolation());
        assertDeleted(f, true);
        assertThat(jdbc.queryForObject("select deleted_at from product where id=?", Timestamp.class, old))
            .isEqualTo(Timestamp.valueOf("2020-01-01 00:00:00"));
        assertThat(jdbc.queryForObject("select updated_at from product where id=?", Timestamp.class, old))
            .isEqualTo(Timestamp.valueOf("2020-01-01 00:00:00"));
        assertThat(jdbc.queryForObject("select deleted_at=updated_at from product where id=?", Boolean.class, f.product()))
            .isTrue();
        assertThat(stock(f.product())).isEqualTo(5);
    }

    @Test
    void requiredJoinsAnExistingRrTransactionAndBothChangesRollback() {
        Fixture f = fixture();
        var tx = tx(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status -> {
            deletion.delete(f.brand());
            assertThat(candidate.getObservedIsolation()).isEqualTo("REPEATABLE-READ");
            status.setRollbackOnly();
        });
        assertDeleted(f, false);
    }

    @Test
    void controllerStartsTheCandidateBoundaryAndKeepsTheResponseContract() throws Exception {
        Fixture f = fixture();
        mvc.perform(delete("/api-admin/v1/brands/" + f.brand()).header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isNoContent());
        assertThat(candidate.getObservedIsolation()).isEqualTo(candidate.getExpectedIsolation());
        assertDeleted(f, true);
        mvc.perform(delete("/api-admin/v1/brands/" + f.brand()).header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isNotFound());
    }

    @Test
    void actualBrandAndBulkSqlRollbackWhenTheCandidateFails() {
        Fixture f = fixture();
        var before = jdbc.queryForList("select * from product order by id");
        candidate.setFailure(true);
        assertThatThrownBy(() -> deletion.delete(f.brand())).isInstanceOf(IllegalStateException.class)
            .hasMessage("candidate failure after executed SQL");
        assertThat(candidate.getObservedDeletedProducts()).isEqualTo(2);
        assertDeleted(f, false);
        assertThat(jdbc.queryForList("select * from product order by id")).isEqualTo(before);
    }

    @Test
    void registrationFirstIsIncludedAfterItsCommit() throws Exception {
        Fixture f = fixture();
        assertThat(race(() -> products.create(f.brand(), "new", 1000, 1),
            () -> deletion.delete(f.brand()), "brand", f.brand(), false)).isNull();
        assertDeleted(f, true);
        assertThat(jdbc.queryForObject("select count(*) from product where brand_id=? and deleted_at is not null",
            Integer.class, f.brand())).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionFirstRegistrationObservesCommitOrRollback(boolean rollback) throws Exception {
        Fixture f = fixture();
        ErrorType result = race(() -> deletion.delete(f.brand()),
            () -> products.create(f.brand(), "new", 1000, 1), "brand", f.brand(), rollback);
        assertThat(result).isEqualTo(rollback ? null : ErrorType.NOT_FOUND);
        assertDeleted(f, !rollback);
        assertThat(jdbc.queryForObject("select count(*) from product where brand_id=? and deleted_at is null",
            Integer.class, f.brand())).isEqualTo(rollback ? 3 : 0);
    }

    @Test
    void productUpdateFirstIsPreservedWhenDeletionContinues() throws Exception {
        Fixture f = fixture();
        assertThat(race(() -> products.update(f.product(), "changed", 2000),
            () -> deletion.delete(f.brand()), "product", f.product(), false)).isNull();
        assertDeleted(f, true);
        assertThat(jdbc.queryForObject("select name from product where id=?", String.class, f.product()))
            .isEqualTo("changed");
        assertThat(stock(f.product())).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionFirstUpdateObservesCommitOrRollback(boolean rollback) throws Exception {
        Fixture f = fixture();
        ErrorType result = race(() -> deletion.delete(f.brand()),
            () -> products.update(f.product(), "changed", 2000), "product", f.product(), rollback);
        assertThat(result).isEqualTo(rollback ? null : ErrorType.NOT_FOUND);
        assertDeleted(f, !rollback);
        assertThat(jdbc.queryForObject("select name from product where id=?", String.class, f.product()))
            .isEqualTo(rollback ? "changed" : "J");
    }

    @Test
    void orderFirstCommitsItsStockReservationBeforeDeletion() throws Exception {
        Fixture f = fixture();
        Buyer b = buyer(f);
        assertThat(race(() -> orders.confirm(b.user(), b.order(), command(b)),
            () -> deletion.delete(f.brand()), "product", f.product(), false)).isNull();
        assertDeleted(f, true);
        assertOrder(b, true);
        assertThat(stock(f.product())).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionFirstOrderObservesCommitOrRollback(boolean rollback) throws Exception {
        Fixture f = fixture();
        Buyer b = buyer(f);
        ErrorType result = race(() -> deletion.delete(f.brand()),
            () -> orders.confirm(b.user(), b.order(), command(b)), "product", f.product(), rollback);
        assertThat(result).isEqualTo(rollback ? null : ErrorType.NOT_FOUND);
        assertDeleted(f, !rollback);
        assertOrder(b, rollback);
        assertThat(stock(f.product())).isEqualTo(rollback ? 3 : 5);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherBrandUpdateAndDeletionFollowThePlanInEitherDirection(boolean deletionFirst) throws Exception {
        Fixture f = fixture();
        Runnable delete = () -> deletion.delete(f.brand());
        Runnable update = () -> products.update(f.otherProduct(), "other changed", 3000);
        boolean expectedWait = false;
        if (allowsPlanDependentUnrelatedWait()) {
            var plan = jdbc.queryForList("explain update product set deleted_at=UTC_TIMESTAMP(6), "
                + "updated_at=UTC_TIMESTAMP(6) where brand_id=? and deleted_at is null", f.brand()).getFirst();
            expectedWait = "PRIMARY".equals(plan.get("key")) || "ALL".equals(plan.get("type"));
        }
        boolean waits = expectedWait;
        var executor = Executors.newSingleThreadExecutor();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            tx(candidate.getExpectedIsolationLevel()).executeWithoutResult(status -> {
                (deletionFirst ? delete : update).run();
                var worker = executor.submit(deletionFirst ? update : delete);
                futures.add(worker);
                if (waits) {
                    awaitLockWait("product", f.otherProduct());
                    assertThat(worker.isDone()).isFalse();
                    return;
                }
                try {
                    worker.get(10, TimeUnit.SECONDS); // Must complete while the first transaction still holds its lock.
                } catch (Exception exception) {
                    throw new IllegalStateException("unrelated brand operation blocked or failed", exception);
                }
            });
            futures.getFirst().get(10, TimeUnit.SECONDS);
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertDeleted(f, true);
        assertThat(jdbc.queryForObject("select name from product where id=?", String.class, f.otherProduct()))
            .isEqualTo("other changed");
    }

    protected boolean allowsPlanDependentUnrelatedWait() {
        return false;
    }

    private ErrorType race(Runnable first, Runnable second, String table, long id, boolean rollback) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<ErrorType>>();
        try {
            tx(candidate.getExpectedIsolationLevel()).executeWithoutResult(status -> {
                first.run();
                futures.add(executor.submit(() -> {
                    try {
                        second.run();
                        return null;
                    } catch (CoreException exception) {
                        return exception.getErrorType();
                    }
                }));
                awaitLockWait(table, id);
                assertThat(futures.getFirst().isDone()).isFalse();
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
            return futures.getFirst().get(15, TimeUnit.SECONDS);
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitLockWait(String table, long id) {
        // Testcontainers' root password is its configured test password. Root is needed for performance_schema.
        try (var app = dataSource.getConnection();
             var root = DriverManager.getConnection(app.getMetaData().getURL(), "root", "test");
             var statement = root.prepareStatement("select count(*) from performance_schema.data_lock_waits w "
                 + "join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id "
                 + "where l.object_schema=database() and l.object_name=? and l.index_name='PRIMARY' and l.lock_data=?")) {
            statement.setString(1, table);
            statement.setString(2, Long.toString(id));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new IllegalStateException("no database lock wait observed on " + table + ":" + id);
        } catch (Exception exception) {
            throw new IllegalStateException("lock wait observation failed", exception);
        }
    }

    private TransactionTemplate tx(int isolation) {
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(isolation);
        tx.setTimeout(20);
        return tx;
    }

    private Fixture fixture() {
        return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status -> {
            long a = brands.save(new BrandModel("A", null)).getId();
            long b = brands.save(new BrandModel("B", null)).getId();
            long j = productRows.save(new ProductModel(a, "J", 1000, 5)).getId();
            long k = productRows.save(new ProductModel(a, "K", 1000, 0)).getId();
            long other = productRows.save(new ProductModel(b, "M", 1000, 7)).getId();
            return new Fixture(a, j, k, b, other);
        });
    }

    private Buyer buyer(Fixture f) {
        long user = users.register().getId();
        points.charge(user, 10000);
        long order = orders.create(user, List.of(new OrderFacade.OrderLine(f.product(), 2))).id();
        return new Buyer(user, order);
    }

    private int stock(long id) {
        return jdbc.queryForObject("select quantity from product where id=?", Integer.class, id);
    }

    private void assertDeleted(Fixture f, boolean deleted) {
        assertThat(jdbc.queryForObject("select deleted_at is not null from brand where id=?", Boolean.class, f.brand()))
            .isEqualTo(deleted);
        assertThat(jdbc.queryForObject("select count(*) from product where id in (?, ?) and deleted_at is null",
            Integer.class, f.product(), f.zeroStock())).isEqualTo(deleted ? 0 : 2);
        assertThat(jdbc.queryForObject("select deleted_at from brand where id=?", Timestamp.class, f.otherBrand()))
            .isNull();
        assertThat(jdbc.queryForObject("select deleted_at from product where id=?", Timestamp.class, f.otherProduct()))
            .isNull();
    }

    private void assertOrder(Buyer b, boolean started) {
        assertThat(jdbc.queryForObject("select status from orders where id=?", String.class, b.order()))
            .isEqualTo(started ? "PAYMENT_PENDING" : "DRAFT");
        assertThat(jdbc.queryForObject("select reservation_status from orders where id=?", String.class, b.order()))
            .isEqualTo(started ? "RESERVED" : null);
        assertThat(jdbc.queryForObject("select balance from point_balance where user_id=?", Long.class, b.user()))
            .isEqualTo(10000L);
    }

    private static OrderFacade.PaymentCommand command(Buyer b) {
        return new OrderFacade.PaymentCommand("attempt-" + b.order(), "TEST", "0000-0000-0000-0000");
    }

    private record Fixture(long brand, long product, long zeroStock, long otherBrand, long otherProduct) {}
    private record Buyer(long user, long order) {}

    @TestConfiguration
    static class CandidateConfig {
        @Bean
        @Primary
        RcBulkFacade rcBulkFacade(BrandService brands, ProductRepository products, EntityManager em, JdbcTemplate jdbc, ApplicationEventPublisher events,
                                 @Value("${candidate.strategy:rc}") String strategy) {
            return switch (strategy) {
                case "rr-hint" -> new RrHintBulkFacade(brands, products, em, jdbc, events);
                case "rr-plain" -> new RrBulkFacade(brands, products, em, jdbc, events);
                default -> new RcBulkFacade(brands, products, em, jdbc, events);
            };
        }
    }

    static class RcBulkFacade extends BrandFacade {
        private final BrandService brands;
        private final EntityManager em;
        private final JdbcTemplate jdbc;
        volatile boolean failAfterSql;
        volatile String observedIsolation;
        volatile int observedDeletedProducts;

        RcBulkFacade(BrandService brands, ProductRepository products, EntityManager em, JdbcTemplate jdbc, ApplicationEventPublisher events) {
            super(brands, products, events);
            this.brands = brands;
            this.em = em;
            this.jdbc = jdbc;
        }

        public void setFailure(boolean fail) {
            failAfterSql = fail;
        }

        public String getObservedIsolation() {
            return observedIsolation;
        }

        public int getObservedDeletedProducts() {
            return observedDeletedProducts;
        }

        public String getExpectedIsolation() {
            return "READ-COMMITTED";
        }

        public int getExpectedIsolationLevel() {
            return TransactionDefinition.ISOLATION_READ_COMMITTED;
        }

        protected String bulkSql() {
            return "update product set deleted_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6) "
                + "where brand_id=? and deleted_at is null";
        }

        @Override
        @Transactional(isolation = Isolation.READ_COMMITTED)
        public void delete(Long id) {
            observedIsolation = jdbc.queryForObject("select @@session.transaction_isolation", String.class);
            brands.delete(id);
            em.flush();
            jdbc.update(bulkSql(), id);
            observedDeletedProducts = jdbc.queryForObject(
                "select count(*) from product where brand_id=? and deleted_at is not null", Integer.class, id);
            if (failAfterSql) {
                throw new IllegalStateException("candidate failure after executed SQL");
            }
            em.clear();
        }
    }

    static class RrHintBulkFacade extends RcBulkFacade {
        RrHintBulkFacade(BrandService brands, ProductRepository products, EntityManager em, JdbcTemplate jdbc, ApplicationEventPublisher events) {
            super(brands, products, em, jdbc, events);
        }

        @Override
        public String getExpectedIsolation() {
            return "REPEATABLE-READ";
        }

        @Override
        public int getExpectedIsolationLevel() {
            return TransactionDefinition.ISOLATION_REPEATABLE_READ;
        }

        @Override
        protected String bulkSql() {
            return "update product FORCE INDEX (idx_product_brand_deleted) "
                + "set deleted_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6) "
                + "where brand_id=? and deleted_at is null";
        }

        @Override
        @Transactional(isolation = Isolation.REPEATABLE_READ)
        public void delete(Long id) {
            super.delete(id);
        }
    }

    static class RrBulkFacade extends RcBulkFacade {
        RrBulkFacade(BrandService brands, ProductRepository products, EntityManager em, JdbcTemplate jdbc, ApplicationEventPublisher events) {
            super(brands, products, em, jdbc, events);
        }

        @Override
        public String getExpectedIsolation() {
            return "REPEATABLE-READ";
        }

        @Override
        public int getExpectedIsolationLevel() {
            return TransactionDefinition.ISOLATION_REPEATABLE_READ;
        }

        @Override
        @Transactional(isolation = Isolation.REPEATABLE_READ)
        public void delete(Long id) {
            super.delete(id);
        }
    }
}
