package com.loopers.infrastructure.product;

import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Product;
import com.loopers.support.error.DomainException;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@EnabledIfEnvironmentVariable(named = "EXPERIMENT", matches = "true")
class BrandProductDeletionScaleTest {

    private static final long TARGET_BRAND = 1L;
    private static final long OTHER_BRAND = 2L;
    private static final int OTHER_PRODUCTS = 10;
    private static final int BUYERS_PER_BRAND = 4;
    private static final List<Integer> SCALES = List.of(10, 100, 1_000, 10_000);
    private static final int SPREAD_BRANDS = 100;
    private static final List<Integer> SPREAD_SCALES = List.of(10, 100, 1_000);
    private static final int MEASURED_RUNS = 3;
    private static final long PRICE = 1_000L;
    private static final int STOCK = 100;
    private static final long TIMEOUT_SECONDS = 120;
    private static final int BATCH_SIZE = 500;
    private static final List<Strategy> CANDIDATES =
        List.of(Strategy.ONE_BY_ONE, Strategy.ONE_BY_ONE_BATCHED, Strategy.BULK, Strategy.BULK_BY_IDS);
    private static final List<Integer> ISOLATIONS =
        List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_READ_COMMITTED);
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    private final EntityManager entityManager;
    private final EntityManagerFactory entityManagerFactory;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final OrderFacade orderFacade;
    private final PointFacade pointFacade;

    @Autowired
    BrandProductDeletionScaleTest(
        EntityManager entityManager,
        EntityManagerFactory entityManagerFactory,
        PlatformTransactionManager transactionManager,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp,
        OrderFacade orderFacade,
        PointFacade pointFacade
    ) {
        this.entityManager = entityManager;
        this.entityManagerFactory = entityManagerFactory;
        this.transactionManager = transactionManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.orderFacade = orderFacade;
        this.pointFacade = pointFacade;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    enum Strategy {
        ONE_BY_ONE("하나씩 · 커밋 때 flush"),
        ONE_BY_ONE_FLUSH_EACH("하나씩 · 저장마다 flush"),
        ONE_BY_ONE_BATCHED("하나씩 · JDBC 배치 " + BATCH_SIZE),
        BULK("벌크 UPDATE 한 문장"),
        BULK_BY_IDS("id 일반 SELECT → id 로 벌크 UPDATE");

        private final String label;

        Strategy(String label) {
            this.label = label;
        }
    }

    enum Distribution {
        SKEWED("편중 · 대상 + 다른 브랜드 1개(" + OTHER_PRODUCTS + "개)", SCALES),
        SPREAD("분산 · 브랜드 " + SPREAD_BRANDS + "개가 같은 수, 기본키 섞임", SPREAD_SCALES);

        private final String label;
        private final List<Integer> scales;

        Distribution(String label, List<Integer> scales) {
            this.label = label;
            this.scales = scales;
        }
    }

    private record DeleteRun(int affected, long totalNanos, long lockHeldNanos) {
    }

    private record Purchase(String group, Long userId, Long orderId) {
    }

    private record Outcome(String group, String result, long nanos) {
    }

    @DisplayName("1 · 혼자 돌 때 규모별로 걸린 시간과 SQL 문 수")
    @Test
    void measuresDeletionAlone() throws IOException {
        StringBuilder report = new StringBuilder("## 1. 혼자 돌 때\n\n")
            .append(environment())
            .append("| 상품 수 | 방식 | 중앙값(ms) | 3회(ms) | SQL 문 수 |\n")
            .append("|---|---|---|---|---|\n");

        for (int scale : SCALES) {
            for (Strategy strategy : Strategy.values()) {
                seed(scale);
                delete(strategy, () -> { });

                List<Long> runs = new ArrayList<>();
                long statements = 0;
                for (int i = 0; i < MEASURED_RUNS; i++) {
                    seed(scale);
                    statistics().clear();
                    DeleteRun run = delete(strategy, () -> { });
                    statements = statistics().getPrepareStatementCount();
                    assertDeleted(run, scale);
                    runs.add(run.totalNanos());
                }
                List<Long> sorted = runs.stream().sorted().toList();
                report.append("| ").append(scale)
                    .append(" | ").append(strategy.label)
                    .append(" | ").append(ms(sorted.get(sorted.size() / 2)))
                    .append(" | ").append(runs.stream().map(BrandProductDeletionScaleTest::ms).collect(joining(" · ")))
                    .append(" | ").append(statements)
                    .append(" |\n");
            }
        }
        write("1-alone.md", report.toString());
    }

    @DisplayName("2 · 삭제가 잠금을 쥔 동안 일반 SELECT 와 잠금 읽기는 각각 어떻게 되는가")
    @Test
    void probesLocksWhileDeletionIsOpen() throws Exception {
        StringBuilder report = new StringBuilder("## 2. 삭제가 커밋 전일 때\n\n")
            .append(environment())
            .append("| 분포 | 브랜드당 상품 수 | 삭제 트랜잭션의 격리 수준 | 통계 | 방식 | 실행 계획 | 잡힌 행 잠금 (인덱스 · 모드 × 개수) "
                + "| 일반 SELECT (ms · 삭제 전 값을 봤나) | 같은 브랜드 상품 FOR UPDATE NOWAIT "
                + "| 기본키가 이웃한 다른 브랜드 상품 FOR UPDATE NOWAIT | 다른 브랜드 상품 INSERT (대기 한도 1초) |\n")
            .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<String> problems = new ArrayList<>();

        for (Distribution distribution : Distribution.values()) {
            for (int scale : distribution.scales) {
                for (int isolation : ISOLATIONS) {
                    for (boolean analyzed : List.of(false, true)) {
                        for (Strategy strategy : CANDIDATES) {
                            report.append(probe(distribution, strategy, scale, isolation, analyzed, problems));
                        }
                    }
                }
            }
        }
        write("2-locks.md", report.toString());
        assertThat(problems).isEmpty();
    }

    @DisplayName("3 · 판매 중에 브랜드를 지우면 같은 브랜드·다른 브랜드의 확정은 각각 얼마나 기다리는가")
    @Test
    void sellsWhileDeleting() throws Exception {
        StringBuilder report = new StringBuilder("## 3. 판매 중 삭제\n\n")
            .append(environment())
            .append("삭제가 잠금을 잡은 직후 확정을 브랜드마다 ").append(BUYERS_PER_BRAND).append("건씩 보낸다.\n\n")
            .append("| 분포 | 브랜드당 상품 수 | 방식 | 삭제 전체(ms) | 잠금을 쥔 시간(ms) | 같은 브랜드 확정 | 다른 브랜드 확정 |\n")
            .append("|---|---|---|---|---|---|---|\n");
        List<String> problems = new ArrayList<>();

        for (Distribution distribution : Distribution.values()) {
            for (int scale : distribution.scales) {
                for (Strategy strategy : CANDIDATES) {
                    report.append(sell(distribution, strategy, scale, problems));
                }
            }
        }
        write("3-selling.md", report.toString());
        assertThat(problems).isEmpty();
    }

    private String probe(
        Distribution distribution, Strategy strategy, int scale, int isolation, boolean analyzed, List<String> problems
    ) throws Exception {
        seed(scale, distribution);
        if (analyzed) {
            jdbcTemplate.execute("ANALYZE TABLE product");
        }
        Long targetProduct = productIds(TARGET_BRAND).get(0);
        Long otherProduct = jdbcTemplate.queryForObject(
            "SELECT MIN(id) FROM product WHERE brand_id <> ? AND id > ?", Long.class, TARGET_BRAND, targetProduct);
        String plan = explain(strategy);

        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setIsolationLevel(isolation);
        AtomicReference<String> appliedIsolation = new AtomicReference<>();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> deletion = executor.submit(() -> isolated.executeWithoutResult(status -> {
                appliedIsolation.set(String.valueOf(entityManager
                    .createNativeQuery("SELECT @@SESSION.transaction_isolation")
                    .getSingleResult()));
                lockAndDeleteBrand();
                deleteProducts(strategy, () -> { });
                entityManager.flush();
                held.countDown();
                await(release);
            }));
            await(held);

            long readStart = System.nanoTime();
            Boolean aliveInSnapshot = jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM product WHERE id = ?", Boolean.class, targetProduct);
            long readNanos = System.nanoTime() - readStart;
            String locks = dataLocks();
            String targetLock = lockNowait(targetProduct);
            String otherLock = lockNowait(otherProduct);
            String otherInsert = insertWithShortWait(OTHER_BRAND);

            release.countDown();
            deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            String label = distribution.label + " · " + scale + " · " + appliedIsolation.get() + " · "
                + (analyzed ? "ANALYZE 후" : "삽입 직후") + " · " + strategy.label;
            String expectedIsolation = isolation == TransactionDefinition.ISOLATION_READ_COMMITTED
                ? "READ-COMMITTED" : "REPEATABLE-READ";
            if (!expectedIsolation.equals(appliedIsolation.get())) {
                problems.add(label + " · 요청한 격리 수준 " + expectedIsolation + " 이 적용되지 않았다");
            }
            if (!Boolean.TRUE.equals(aliveInSnapshot)) {
                problems.add(label + " · 일반 SELECT 가 커밋 전 삭제를 봤다");
            }
            if (targetLock.startsWith("즉시")) {
                problems.add(label + " · 같은 브랜드 상품이 잠겨 있지 않았다");
            }
            if (aliveCount(TARGET_BRAND) != 0) {
                problems.add(label + " · 커밋 뒤 살아 있는 상품이 남았다");
            }

            return "| " + distribution.label
                + " | " + scale
                + " | " + appliedIsolation.get()
                + " | " + (analyzed ? "ANALYZE 후" : "삽입 직후")
                + " | " + strategy.label
                + " | " + plan
                + " | " + locks
                + " | " + ms(readNanos) + " · " + (Boolean.TRUE.equals(aliveInSnapshot) ? "삭제 전 값" : "삭제 후 값")
                + " | " + targetLock
                + " | " + otherLock
                + " | " + otherInsert
                + " |\n";
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    private String sell(Distribution distribution, Strategy strategy, int scale, List<String> problems)
        throws Exception {
        seed(scale, distribution);
        List<Long> targets = productIds(TARGET_BRAND);
        List<Long> others = productIds(OTHER_BRAND);
        List<Purchase> purchases = new ArrayList<>();
        for (int i = 0; i < BUYERS_PER_BRAND; i++) {
            purchases.add(draft("같은 브랜드", 1_000L + i, targets.get(i)));
            purchases.add(draft("다른 브랜드", 2_000L + i, others.get(i)));
        }

        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(1 + purchases.size());
        try {
            Future<DeleteRun> deletion = executor.submit(() -> delete(strategy, locked::countDown));
            await(locked);
            List<Future<Outcome>> confirms = purchases.stream()
                .map(purchase -> executor.submit(() -> confirm(purchase)))
                .toList();

            DeleteRun run = deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> confirm : confirms) {
                outcomes.add(confirm.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            String label = distribution.label + " · " + scale + " · " + strategy.label;
            if (outcomes.size() != purchases.size()) {
                problems.add(label + " · 집계가 요청 수와 다르다");
            }
            outcomes.stream()
                .filter(outcome -> outcome.result().startsWith("기술 오류"))
                .forEach(outcome -> problems.add(label + " · " + outcome.group() + " " + outcome.result()));
            outcomes.stream()
                .filter(outcome -> outcome.group().equals("다른 브랜드") && !outcome.result().equals("성공"))
                .forEach(outcome -> problems.add(label + " · 다른 브랜드 확정이 실패했다: " + outcome.result()));
            if (aliveCount(TARGET_BRAND) != 0) {
                problems.add(label + " · 커밋 뒤 살아 있는 상품이 남았다");
            }

            return "| " + distribution.label
                + " | " + scale
                + " | " + strategy.label
                + " | " + ms(run.totalNanos())
                + " | " + ms(run.lockHeldNanos())
                + " | " + summary(outcomes, "같은 브랜드")
                + " | " + summary(outcomes, "다른 브랜드")
                + " |\n";
        } finally {
            shutdown(executor);
        }
    }

    private DeleteRun delete(Strategy strategy, Runnable onLocked) {
        AtomicLong lockedAt = new AtomicLong();
        long start = System.nanoTime();
        Integer affected = transactionTemplate.execute(status -> {
            lockAndDeleteBrand();
            return deleteProducts(strategy, () -> {
                lockedAt.set(System.nanoTime());
                onLocked.run();
            });
        });
        long end = System.nanoTime();
        return new DeleteRun(affected, end - start, end - lockedAt.get());
    }

    private void lockAndDeleteBrand() {
        entityManager.createNativeQuery("SELECT id FROM brand WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
            .setParameter("id", TARGET_BRAND)
            .getResultList();
        entityManager.createNativeQuery("UPDATE brand SET deleted_at = NOW(6), updated_at = NOW(6) WHERE id = :id")
            .setParameter("id", TARGET_BRAND)
            .executeUpdate();
    }

    private int deleteProducts(Strategy strategy, Runnable locked) {
        if (strategy == Strategy.BULK) {
            ZonedDateTime now = ZonedDateTime.now();
            int updated = entityManager.createQuery(
                    "update ProductEntity p set p.deletedAt = :now, p.updatedAt = :now "
                        + "where p.brandId = :brandId and p.deletedAt is null")
                .setParameter("now", now)
                .setParameter("brandId", TARGET_BRAND)
                .executeUpdate();
            locked.run();
            return updated;
        }

        if (strategy == Strategy.BULK_BY_IDS) {
            List<Long> ids = entityManager.createQuery(
                    "select p.id from ProductEntity p where p.brandId = :brandId and p.deletedAt is null",
                    Long.class)
                .setParameter("brandId", TARGET_BRAND)
                .getResultList();
            if (ids.isEmpty()) {
                locked.run();
                return 0;
            }
            ZonedDateTime now = ZonedDateTime.now();
            int updated = entityManager.createQuery(
                    "update ProductEntity p set p.deletedAt = :now, p.updatedAt = :now "
                        + "where p.id in :ids and p.deletedAt is null")
                .setParameter("now", now)
                .setParameter("ids", ids)
                .executeUpdate();
            locked.run();
            return updated;
        }

        List<ProductEntity> products = entityManager.createQuery(
                "select p from ProductEntity p where p.brandId = :brandId and p.deletedAt is null order by p.id",
                ProductEntity.class)
            .setParameter("brandId", TARGET_BRAND)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
        locked.run();
        if (strategy == Strategy.ONE_BY_ONE_BATCHED) {
            entityManager.unwrap(Session.class).setJdbcBatchSize(BATCH_SIZE);
        }
        for (ProductEntity entity : products) {
            Product product = entity.toDomain();
            product.delete();
            entity.apply(product);
            if (strategy == Strategy.ONE_BY_ONE_FLUSH_EACH) {
                entityManager.flush();
            }
        }
        return products.size();
    }

    private Purchase draft(String group, Long userId, Long productId) {
        pointFacade.charge(userId, ChargeAmount.of(100_000), NOW);
        Long orderId = orderFacade.place(
            new OrderCreateCommand(userId, List.of(new OrderCreateCommand.Line(productId, OrderQuantity.of(1)))),
            NOW
        ).getId();
        return new Purchase(group, userId, orderId);
    }

    private Outcome confirm(Purchase purchase) {
        long start = System.nanoTime();
        String result;
        try {
            orderFacade.confirm(purchase.userId(), purchase.orderId(), NOW);
            result = "성공";
        } catch (DomainException e) {
            result = "거절 " + e.code();
        } catch (RuntimeException e) {
            result = "기술 오류 " + e.getClass().getSimpleName();
        }
        return new Outcome(purchase.group(), result, System.nanoTime() - start);
    }

    private void seed(int targetProducts) {
        seed(targetProducts, Distribution.SKEWED);
    }

    private void seed(int perBrand, Distribution distribution) {
        databaseCleanUp.truncateAllTables();
        if (distribution == Distribution.SKEWED) {
            insertBrand(TARGET_BRAND, "지울 브랜드");
            insertBrand(OTHER_BRAND, "남을 브랜드");
            insertProducts(TARGET_BRAND, perBrand);
            insertProducts(OTHER_BRAND, OTHER_PRODUCTS);
            return;
        }
        for (long brandId = 1; brandId <= SPREAD_BRANDS; brandId++) {
            insertBrand(brandId, "브랜드-" + brandId);
        }
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < perBrand; i++) {
            for (long brandId = 1; brandId <= SPREAD_BRANDS; brandId++) {
                rows.add(new Object[] {brandId, "상품-" + brandId + "-" + i, PRICE, STOCK});
            }
        }
        jdbcTemplate.batchUpdate(
            "INSERT INTO product (brand_id, name, price, quantity, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
            rows);
    }

    private void insertBrand(long brandId, String name) {
        jdbcTemplate.update(
            "INSERT INTO brand (id, name, description, created_at, updated_at) VALUES (?, ?, NULL, NOW(6), NOW(6))",
            brandId, name);
    }

    private void insertProducts(long brandId, int count) {
        List<Object[]> rows = IntStream.range(0, count)
            .mapToObj(i -> new Object[] {brandId, "상품-" + brandId + "-" + i, PRICE, STOCK})
            .toList();
        jdbcTemplate.batchUpdate(
            "INSERT INTO product (brand_id, name, price, quantity, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
            rows);
    }

    private List<Long> productIds(long brandId) {
        return jdbcTemplate.queryForList("SELECT id FROM product WHERE brand_id = ? ORDER BY id", Long.class, brandId);
    }

    private int aliveCount(long brandId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM product WHERE brand_id = ? AND deleted_at IS NULL", Integer.class, brandId);
        return count == null ? 0 : count;
    }

    private void assertDeleted(DeleteRun run, int scale) {
        assertThat(run.affected()).isEqualTo(scale);
        assertThat(aliveCount(TARGET_BRAND)).isZero();
        assertThat(aliveCount(OTHER_BRAND)).isEqualTo(OTHER_PRODUCTS);
    }

    private String explain(Strategy strategy) {
        if (strategy == Strategy.BULK_BY_IDS) {
            String ids = productIds(TARGET_BRAND).stream().map(String::valueOf).collect(joining(","));
            return "id 조회: " + plan(jdbcTemplate.queryForList(
                    "EXPLAIN SELECT id FROM product WHERE brand_id = ? AND deleted_at IS NULL", TARGET_BRAND).get(0))
                + "<br>UPDATE: " + plan(jdbcTemplate.queryForList(
                    "EXPLAIN UPDATE product SET deleted_at = NOW(6) WHERE id IN (" + ids + ") AND deleted_at IS NULL")
                .get(0));
        }
        String sql = strategy == Strategy.BULK
            ? "EXPLAIN UPDATE product SET deleted_at = NOW(6) WHERE brand_id = ? AND deleted_at IS NULL"
            : "EXPLAIN SELECT * FROM product WHERE brand_id = ? AND deleted_at IS NULL ORDER BY id FOR UPDATE";
        return plan(jdbcTemplate.queryForList(sql, TARGET_BRAND).get(0));
    }

    private static String plan(Map<String, Object> row) {
        return "type=" + row.get("type") + " · key=" + row.get("key") + " · rows=" + row.get("rows")
            + " · " + row.get("Extra");
    }

    private String lockNowait(Long productId) {
        try {
            jdbcTemplate.queryForList("SELECT id FROM product WHERE id = ? FOR UPDATE NOWAIT", productId);
            return "즉시 획득";
        } catch (DataAccessException e) {
            return "막힘 (" + e.getMostSpecificCause().getMessage() + ")";
        }
    }

    private String dataLocks() {
        String sql = "SELECT INDEX_NAME, LOCK_MODE, COUNT(*), SUM(LOCK_DATA = 'supremum pseudo-record') "
            + "FROM performance_schema.data_locks "
            + "WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = 'product' AND LOCK_TYPE = 'RECORD' "
            + "GROUP BY INDEX_NAME, LOCK_MODE ORDER BY INDEX_NAME, LOCK_MODE";
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
                "root",
                System.getProperty("datasource.mysql-jpa.main.password"));
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<String> locks = new ArrayList<>();
            while (rows.next()) {
                locks.add(rows.getString(1) + " · " + rows.getString(2) + " × " + rows.getLong(3)
                    + (rows.getLong(4) > 0 ? " (supremum 포함)" : ""));
            }
            return locks.isEmpty() ? "없음" : String.join("<br>", locks);
        } catch (SQLException e) {
            return "조회 불가 (" + e.getMessage() + ")";
        }
    }

    private String insertWithShortWait(long brandId) {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
                connection.setAutoCommit(false);
                long start = System.nanoTime();
                try {
                    statement.executeUpdate(
                        "INSERT INTO product (brand_id, name, price, quantity, created_at, updated_at) "
                            + "VALUES (" + brandId + ", '갭 확인', 1000, 1, NOW(6), NOW(6))");
                    return "즉시 성공 (" + ms(System.nanoTime() - start) + "ms)";
                } catch (SQLException e) {
                    return "막힘 (" + e.getMessage() + ")";
                } finally {
                    connection.rollback();
                    statement.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                    connection.setAutoCommit(autoCommit);
                }
            }
        });
    }

    private String environment() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT VERSION() AS version, @@GLOBAL.transaction_isolation AS globalIsolation, "
                + "@@SESSION.transaction_isolation AS sessionIsolation, "
                + "@@GLOBAL.binlog_format AS binlogFormat, @@innodb_lock_wait_timeout AS lockWait");
        return "MySQL " + row.get("version")
            + " · 격리 수준 GLOBAL " + row.get("globalIsolation")
            + " / SESSION " + row.get("sessionIsolation")
            + " · binlog_format " + row.get("binlogFormat")
            + " · 잠금 대기 한도 " + row.get("lockWait") + "초\n\n";
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static String summary(List<Outcome> outcomes, String group) {
        List<Outcome> mine = outcomes.stream().filter(outcome -> outcome.group().equals(group)).toList();
        String results = mine.stream()
            .collect(groupingBy(Outcome::result, TreeMap::new, counting()))
            .entrySet().stream()
            .map(entry -> entry.getKey() + " × " + entry.getValue())
            .collect(joining(", "));
        long longest = mine.stream().mapToLong(Outcome::nanos).max().orElse(0);
        return results + " · 최대 " + ms(longest) + "ms";
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1_000_000.0);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 한도 " + TIMEOUT_SECONDS + "초를 넘었습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("worker 가 끝나지 않았습니다");
        }
    }

    private static void write(String fileName, String content) throws IOException {
        Path directory = Path.of("build", "experiments", "brand-product-deletion");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), content);
        System.out.println(content);
    }
}
