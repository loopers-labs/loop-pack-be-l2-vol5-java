package com.loopers.application.brand;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 현재 facade와 테스트 전용 벌크 SQL을 비교한다. 서비스 삭제 방식은 변경하지 않는다.
 */
@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.org.hibernate.stat=OFF",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class BrandBulkDeletionExperimentTest {
    private static final String BULK_SQL = """
        update product set deleted_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
        where brand_id = :brandId and deleted_at is null
        """;
    private static final Timestamp OLD_DELETION = Timestamp.valueOf("2020-01-01 00:00:00");

    @Autowired private BrandFacade facade;
    @Autowired private ProductFacade products;
    @Autowired private BrandService brandService;
    @Autowired private BrandJpaRepository brands;
    @Autowired private OrderJpaRepository orders;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private DatabaseCleanUp cleanUp;

    enum Strategy { CURRENT, BULK }

    @AfterEach
    void clean() {
        cleanUp.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 100, 1000})
    void comparesCommittedDeletionWithTheSameData(int size) {
        // 각 방식으로 한 번 준비 실행한다. 데이터 준비·결과 검증은 측정 구간에서 제외한다.
        for (Strategy strategy : Strategy.values()) {
            Fixture fixture = prepare(size);
            transaction.executeWithoutResult(status -> delete(strategy, fixture));
            verify(fixture, true);
            clean();
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        for (int round = 0; round < 3; round++) {
            List<Strategy> sequence = round % 2 == 0
                ? List.of(Strategy.CURRENT, Strategy.BULK) : List.of(Strategy.BULK, Strategy.CURRENT);
            for (Strategy strategy : sequence) {
                Fixture fixture = prepare(size);
                statistics.clear();
                long start = System.nanoTime();
                transaction.executeWithoutResult(status -> delete(strategy, fixture));
                double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
                long statements = statistics.getPrepareStatementCount();
                verify(fixture, true);
                System.out.printf("BULK_TIMING size=%d strategy=%s round=%d ms=%.3f statements=%d%n",
                    size, strategy, round + 1, milliseconds, statements);
                clean();
            }
        }
    }

    static Stream<Arguments> lockCases() {
        return Stream.of(10, 1000).flatMap(size -> Stream.of(Strategy.values())
            .map(strategy -> Arguments.of(size, strategy)));
    }

    @ParameterizedTest
    @MethodSource("lockCases")
    void observesLockScopeBeforeCommit(int size, Strategy strategy) {
        Fixture fixture = prepare(size);
        System.out.println("BULK_PLAN size=" + size + " " + jdbc.queryForList(
            "explain update product set deleted_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) "
                + "where brand_id = ? and deleted_at is null", fixture.brandId()));
        transaction.executeWithoutResult(status -> {
            String isolation = jdbc.queryForObject("select @@session.transaction_isolation", String.class);
            delete(strategy, fixture);
            entityManager.flush();
            assertThat(lockUnavailable(fixture.targetId())).isTrue();
            boolean otherBlocked = lockUnavailable(fixture.otherId());
            if (strategy == Strategy.CURRENT) {
                assertThat(otherBlocked).isFalse();
            }
            // 벌크 잠금 범위는 관찰 결과다. 범위가 넓어도 기록하고 채택 여부는 따로 판단한다.
            System.out.printf("BULK_SCOPE size=%d strategy=%s isolation=%s otherBlocked=%s%n",
                size, strategy, isolation, otherBlocked);
        });
        assertThat(lockUnavailable(fixture.targetId())).isFalse();
        assertThat(lockUnavailable(fixture.otherId())).isFalse();
        verify(fixture, true);
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void rollsBackExecutedSqlAndPreservesExistingHistory(Strategy strategy) {
        Fixture fixture = prepare(10);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            delete(strategy, fixture);
            entityManager.flush();
            assertThat(countActive(fixture.brandId())).isZero();
            assertThat(jdbc.queryForObject("select deleted_at from brand where id = ?",
                Timestamp.class, fixture.brandId())).isNotNull();
            throw new IllegalStateException("experiment: failure after deletion SQL");
        })).isInstanceOf(IllegalStateException.class)
            .hasMessage("experiment: failure after deletion SQL");
        // 바깥 트랜잭션 종료 후 JDBC로 재조회한다. 관리 중인 객체로 판단하지 않는다.
        verify(fixture, false);
    }

    static Stream<Arguments> distributionCases() {
        return Stream.of(2, 100).flatMap(count -> Stream.of(false, true)
            .flatMap(analyzed -> Stream.of(Strategy.values())
                .flatMap(strategy -> Stream.of(false, true)
                    .map(rc -> Arguments.of(count, analyzed, strategy, rc)))));
    }

    @ParameterizedTest
    @MethodSource("distributionCases")
    void comparesDistributionAndActualProductUpdate(int brandCount, boolean analyzed, Strategy strategy, boolean rc)
        throws Exception {
        TransactionTemplate experiment = new TransactionTemplate(transaction.getTransactionManager());
        experiment.setIsolationLevel(rc ? TransactionDefinition.ISOLATION_READ_COMMITTED
            : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        String isolation = rc ? "READ-COMMITTED" : "REPEATABLE-READ";
        Fixture fixture = prepare(100);
        List<Long> extraBrands = new ArrayList<>();
        for (int i = 2; i < brandCount; i++) {
            extraBrands.add(brands.save(new BrandModel("extra " + i, null)).getId());
        }
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            for (long id : extraBrands) {
                rows.add(new Object[] {id, "extra", 1000, 5});
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("insert into product (brand_id, name, price, quantity, created_at, updated_at) "
                + "values (?, ?, ?, ?, '2020-01-01', '2020-01-01')", rows);
        }
        if (analyzed) {
            jdbc.queryForList("analyze table product");
        }
        System.out.printf("DISTRIBUTION_PLAN isolation=%s brands=%d analyzed=%s strategy=%s plan=%s%n",
            isolation, brandCount, analyzed, strategy, jdbc.queryForList(
                "explain update product set deleted_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) "
                    + "where brand_id = ? and deleted_at is null", fixture.brandId()));
        var executor = Executors.newFixedThreadPool(2);
        try {
            // 이미 삭제 SQL이 실행된 상태에서 실제 수정 서비스를 호출한다.
            // 최대 200ms 커밋을 미루는 인위적 실험이며 정상 응답 지연으로 해석하지 않는다.
            long[] heldUpdateNanos = new long[1];
            java.util.concurrent.Future<?>[] held = new java.util.concurrent.Future<?>[1];
            experiment.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("select @@session.transaction_isolation", String.class))
                    .isEqualTo(isolation);
                delete(strategy, fixture);
                entityManager.flush();
                boolean blocked = lockUnavailable(fixture.otherId());
                held[0] = executor.submit(() -> {
                    long start = System.nanoTime();
                    products.update(fixture.otherId(), "modified", 2000);
                    heldUpdateNanos[0] = System.nanoTime() - start;
                });
                boolean finished = false;
                try {
                    held[0].get(200, TimeUnit.MILLISECONDS);
                    finished = true;
                } catch (TimeoutException expected) {
                    // 잠금 충돌 여부는 위 NOWAIT 검사로 구분한다.
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                System.out.printf("DISTRIBUTION_HELD isolation=%s brands=%d analyzed=%s strategy=%s blocked=%s "
                    + "updateFinishedBeforeCommit=%s%n", isolation, brandCount, analyzed, strategy, blocked, finished);
            });
            held[0].get(10, TimeUnit.SECONDS);
            System.out.printf("DISTRIBUTION_HELD_TIME isolation=%s brands=%d analyzed=%s strategy=%s ms=%.3f%n",
                isolation, brandCount, analyzed, strategy, heldUpdateNanos[0] / 1_000_000.0);
            assertThat(jdbc.queryForObject("select name from product where id = ?", String.class,
                fixture.otherId())).isEqualTo("modified");
            for (int round = 0; round < 3; round++) {
                jdbc.update("update brand set deleted_at = null where id = ?", fixture.brandId());
                jdbc.update("update product set deleted_at = null where brand_id = ? and id <> ?",
                    fixture.brandId(), fixture.oldId());
                String updateName = "concurrent " + round;
                CountDownLatch start = new CountDownLatch(1);
                var deletion = executor.submit(() -> {
                    awaitStart(start);
                    long began = System.nanoTime();
                    experiment.executeWithoutResult(status -> delete(strategy, fixture));
                    return (System.nanoTime() - began) / 1_000_000.0;
                });
                var update = executor.submit(() -> {
                    awaitStart(start);
                    long began = System.nanoTime();
                    products.update(fixture.otherId(), updateName, 3000);
                    return (System.nanoTime() - began) / 1_000_000.0;
                });
                start.countDown();
                double deletionMs = deletion.get(20, TimeUnit.SECONDS);
                double updateMs = update.get(20, TimeUnit.SECONDS);
                System.out.printf("DISTRIBUTION_NATURAL isolation=%s brands=%d analyzed=%s strategy=%s round=%d "
                    + "deleteMs=%.3f updateMs=%.3f%n", isolation, brandCount, analyzed, strategy, round, deletionMs, updateMs);
                assertThat(countActive(fixture.brandId())).isZero();
                assertThat(countActive(fixture.otherBrandId())).isEqualTo(100);
                assertThat(jdbc.queryForObject("select name from product where id = ?", String.class,
                    fixture.otherId())).isEqualTo(updateName);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitStart(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void delete(Strategy strategy, Fixture fixture) {
        if (strategy == Strategy.CURRENT) {
            facade.delete(fixture.brandId());
            return;
        }
        // 제품 repository는 변경하지 않는다. 같은 브랜드 잠금과 트랜잭션 경계를 사용한다.
        brandService.delete(fixture.brandId());
        entityManager.flush();
        int changed = entityManager.createNativeQuery(BULK_SQL)
            .setParameter("brandId", fixture.brandId()).executeUpdate();
        assertThat(changed).isEqualTo(fixture.size());
        // 상품을 미리 읽지 않고, 오래된 상품 객체를 다시 저장하지 않는다.
    }

    private Fixture prepare(int size) {
        long targetBrand = brands.save(new BrandModel("target", null)).getId();
        long otherBrand = brands.save(new BrandModel("other", null)).getId();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            // 다른 브랜드 상품과 ID가 교차한다. 대상 비율은 약 50%다.
            rows.add(new Object[] {targetBrand, "target " + i, 1000, i % 2 == 0 ? 0 : 5});
            rows.add(new Object[] {otherBrand, "other " + i, 1000, 5});
        }
        jdbc.batchUpdate("insert into product (brand_id, name, price, quantity, created_at, updated_at) "
            + "values (?, ?, ?, ?, '2020-01-01', '2020-01-01')", rows);
        jdbc.update("insert into product (brand_id, name, price, quantity, created_at, updated_at, deleted_at) "
            + "values (?, 'old deleted', 1000, 5, ?, ?, ?)",
            targetBrand, OLD_DELETION, OLD_DELETION, OLD_DELETION);
        long targetId = jdbc.queryForObject("select min(id) from product where brand_id = ?", Long.class, targetBrand);
        long otherId = jdbc.queryForObject("select min(id) from product where brand_id = ?", Long.class, otherBrand);
        long oldId = jdbc.queryForObject("select max(id) from product where brand_id = ?", Long.class, targetBrand);
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(targetId, "purchase snapshot", 1000, 2)));
        order.confirm();
        long orderId = orders.save(order).getId();
        return new Fixture(targetBrand, otherBrand, targetId, otherId, oldId, size, orderId,
            productValues(), jdbc.queryForMap("select * from orders where id = ?", orderId),
            jdbc.queryForList("select * from order_item where order_id = ?", orderId));
    }

    private void verify(Fixture fixture, boolean deleted) {
        assertThat(countActive(fixture.brandId())).isEqualTo(deleted ? 0 : fixture.size());
        assertThat(countActive(fixture.otherBrandId())).isEqualTo(fixture.size());
        assertThat(jdbc.queryForObject("select deleted_at from brand where id = ?",
            Timestamp.class, fixture.brandId()) != null).isEqualTo(deleted);
        assertThat(jdbc.queryForObject("select deleted_at from brand where id = ?",
            Timestamp.class, fixture.otherBrandId())).isNull();
        assertThat(jdbc.queryForObject("select deleted_at from product where id = ?",
            Timestamp.class, fixture.oldId())).isEqualTo(OLD_DELETION);
        assertThat(jdbc.queryForObject("select updated_at from product where id = ?",
            Timestamp.class, fixture.oldId())).isEqualTo(OLD_DELETION);
        assertThat(jdbc.queryForObject("select updated_at from product where id = ?",
            Timestamp.class, fixture.otherId())).isEqualTo(OLD_DELETION);
        assertThat(jdbc.queryForObject("select updated_at from product where id = ?",
            Timestamp.class, fixture.targetId()).after(OLD_DELETION)).isEqualTo(deleted);
        assertThat(productValues()).isEqualTo(fixture.productValues());
        assertThat(jdbc.queryForMap("select * from orders where id = ?", fixture.orderId()))
            .isEqualTo(fixture.order());
        assertThat(jdbc.queryForList("select * from order_item where order_id = ?", fixture.orderId()))
            .isEqualTo(fixture.items());
    }

    private List<Map<String, Object>> productValues() {
        return jdbc.queryForList("select id, brand_id, name, price, quantity, created_at from product order by id");
    }

    private int countActive(long brandId) {
        return jdbc.queryForObject("select count(*) from product where brand_id = ? and deleted_at is null",
            Integer.class, brandId);
    }

    private boolean lockUnavailable(long productId) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("select id from product where id = ? for update nowait")) {
                query.setQueryTimeout(5);
                query.setLong(1, productId);
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
                return false;
            } catch (SQLException exception) {
                if (exception.getErrorCode() != 3572) {
                    throw new IllegalStateException("unexpected SQL failure", exception);
                }
                return true;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("probe connection failure", exception);
        }
    }

    private record Fixture(long brandId, long otherBrandId, long targetId, long otherId, long oldId,
                           int size, long orderId, List<Map<String, Object>> productValues,
                           Map<String, Object> order, List<Map<String, Object>> items) {}
}
