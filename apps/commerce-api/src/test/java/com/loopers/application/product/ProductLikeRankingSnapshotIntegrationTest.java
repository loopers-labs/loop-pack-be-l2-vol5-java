package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductListSnapshot;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductLikeRankingSnapshotIntegrationTest {
    @Autowired private ProductListReader reader;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp cleanup;
    @MockitoSpyBean(name = "mySqlMainDataSource") private HikariDataSource dataSource;

    private final ThreadLocal<Boolean> observeReader = ThreadLocal.withInitial(() -> false);
    private RankingReadGate gate;
    private JdbcTemplate jdbc;
    private long brandId;
    private long otherBrandId;
    private long productId;
    private long otherProductId;
    private boolean workersTerminated = true;

    @BeforeEach
    void setUp() throws SQLException {
        gate = new RankingReadGate();
        doAnswer(invocation -> {
            Connection actual = (Connection) invocation.callRealMethod();
            return observeReader.get() ? gate.wrap(actual) : actual;
        }).when(dataSource).getConnection();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        Brand brand = brands.save(new Brand("순위 스냅샷 브랜드"));
        Brand otherBrand = brands.save(new Brand("보존할 브랜드"));
        brandId = brand.getId();
        otherBrandId = otherBrand.getId();
        productId = products.save(new Product(brand, "변경 대상 상품", 100, 5)).getId();
        otherProductId = products.save(new Product(otherBrand, "보존할 상품", 200, 3)).getId();
        users.save(new User(42));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> revisions.advance());
    }

    @AfterEach
    void tearDown() {
        gate.allowRead.countDown();
        assertThat(workersTerminated).as("조회 worker가 종료되지 않으면 DB를 정리하지 않는다").isTrue();
        cleanup.truncateAllTables();
        observeReader.remove();
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    @Timeout(45)
    void rankAndDetailsStayInOneSnapshotAcrossACommittedMutation(Mutation mutation) throws Exception {
        assertThat(AopUtils.isAopProxy(reader)).as("실제 Spring ProductListReader 트랜잭션 프록시").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var executor = Executors.newSingleThreadExecutor();
        Future<ProductListSnapshot> reading = null;
        ProductListSnapshot duringChange;
        Long writerConnectionId;
        workersTerminated = false;
        try {
            reading = executor.submit(() -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                observeReader.set(true);
                try {
                    // 외부 테스트 트랜잭션을 추가하지 않고 실제 캐시 miss의 snapshot 경계를 호출한다.
                    return reader.readSnapshot();
                } finally {
                    observeReader.remove();
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                }
            });
            assertThat(gate.rankingExecuted.await(10, TimeUnit.SECONDS))
                .as("실제 좋아요 순위 SELECT 실행 직후, 상세 IN 조회 이전에 멈춘다").isTrue();

            TransactionTemplate writer = new TransactionTemplate(transactionManager);
            writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            writer.setTimeout(10);
            writerConnectionId = writer.execute(status -> {
                assertThat(status.isNewTransaction()).isTrue();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                Long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                mutate(mutation);
                revisions.advance();
                return connectionId;
            });
            // writer.execute가 정상 반환하고 독립 재조회도 새 상태를 확인한 뒤 reader를 재개한다.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(revisions.current()).isEqualTo(2);
            assertCommittedMutation(mutation);
            gate.allowRead.countDown();
            duringChange = reading.get(10, TimeUnit.SECONDS);
        } finally {
            gate.allowRead.countDown();
            if (reading != null) {
                reading.cancel(true);
            }
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            assertThat(workersTerminated).as("결과 검증과 DB 정리 전에 조회 worker 종료").isTrue();
        }

        assertThat(duringChange.revision()).isEqualTo(1);
        assertThat(duringChange.page().totalElements()).isEqualTo(2);
        assertThat(duringChange.page().totalPages()).isEqualTo(1);
        assertThat(duringChange.page().items()).containsExactly(otherItem(), originalItem());
        assertReadBoundary(writerConnectionId);

        ProductListSnapshot afterChange = reader.readSnapshot();
        assertThat(afterChange.revision()).isEqualTo(2);
        assertThat(afterChange.page().totalPages()).isEqualTo(1);
        if (mutation == Mutation.PRICE_AND_LIKE) {
            assertThat(afterChange.page().totalElements()).isEqualTo(2);
            assertThat(afterChange.page().items()).containsExactly(new ProductListSnapshot.Item(
                productId, "변경 대상 상품", 500, 5, brandId, "순위 스냅샷 브랜드", 1), otherItem());
        } else {
            assertThat(afterChange.page().totalElements()).isEqualTo(1);
            assertThat(afterChange.page().items()).containsExactly(otherItem());
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void mutate(Mutation mutation) {
        switch (mutation) {
            case PRICE_AND_LIKE -> {
                assertThat(jdbc.update("UPDATE product SET price = ? WHERE id = ?", 500, productId)).isEqualTo(1);
                assertThat(jdbc.update("INSERT INTO `like` (user_id, product_id, created_at) "
                    + "VALUES (?, ?, CURRENT_TIMESTAMP(6))", 42, productId)).isEqualTo(1);
            }
            case PRODUCT_DELETE -> assertThat(jdbc.update(
                "UPDATE product SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", productId)).isEqualTo(1);
            // 상품 자체가 미삭제여도 브랜드의 삭제 조건만으로 다음 고객 조회에서 제외되어야 한다.
            case BRAND_DELETE -> assertThat(jdbc.update(
                "UPDATE brand SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", brandId)).isEqualTo(1);
        }
    }

    private void assertCommittedMutation(Mutation mutation) {
        switch (mutation) {
            case PRICE_AND_LIKE -> {
                assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, productId))
                    .isEqualTo(500);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like` WHERE product_id = ?", Long.class, productId))
                    .isEqualTo(1);
            }
            case PRODUCT_DELETE -> assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM product WHERE id = ?", Boolean.class, productId)).isTrue();
            case BRAND_DELETE -> assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM brand WHERE id = ?", Boolean.class, brandId)).isTrue();
        }
    }

    private ProductListSnapshot.Item originalItem() {
        return new ProductListSnapshot.Item(productId, "변경 대상 상품", 100, 5, brandId, "순위 스냅샷 브랜드", 0);
    }

    private ProductListSnapshot.Item otherItem() {
        return new ProductListSnapshot.Item(otherProductId, "보존할 상품", 200, 3,
            otherBrandId, "보존할 브랜드", 0);
    }

    private void assertReadBoundary(Long writerConnectionId) {
        List<ReadEvidence> reads = List.copyOf(gate.reads);
        assertThat(reads).as("버전·전체 개수·좁은 순위 집계·선정 ID 상세 조회의 네 SELECT").hasSize(4);
        assertThat(reads.getFirst().sql()).contains("from product_list_revision");
        assertThat(reads.get(1).sql()).startsWith("select count(");
        ReadEvidence rank = reads.get(2);
        assertThat(rank.sql()).contains("from product ", "join `like`", "group by", "order by");
        assertThat(rank.columns()).as("순위 단계는 ID와 좋아요 수만 반환한다").isEqualTo(2);
        ReadEvidence detail = reads.getLast();
        assertThat(detail.sql()).contains("from product ", " in (")
            .doesNotContain("join `like`", "group by");
        assertThat(detail.columns()).as("선정된 상품의 상세만 조회하고 좋아요 수는 앞 순위에서 보존한다").isEqualTo(9);
        long readerConnectionId = reads.getFirst().connectionId();
        assertThat(writerConnectionId).isNotNull().isNotEqualTo(readerConnectionId);
        assertThat(reads).allSatisfy(read -> {
            assertThat(read.connectionId()).isEqualTo(readerConnectionId);
            assertThat(read.isolation()).isEqualTo("REPEATABLE-READ");
            assertThat(read.autoCommit()).isFalse();
            assertThat(read.transactionActive()).isTrue();
            assertThat(read.readOnly()).isTrue();
            assertThat(read.sql()).doesNotContain("for update", "for share");
        });
    }

    enum Mutation {
        PRICE_AND_LIKE, PRODUCT_DELETE, BRAND_DELETE
    }

    /** SQL·결과는 실제 MySQL이 만든 값을 사용하며 집계와 상세 사이의 실행 시점만 제어한다. */
    private static final class RankingReadGate {
        private final CountDownLatch rankingExecuted = new CountDownLatch(1);
        private final CountDownLatch allowRead = new CountDownLatch(1);
        private final AtomicBoolean paused = new AtomicBoolean();
        private final ConcurrentLinkedQueue<ReadEvidence> reads = new ConcurrentLinkedQueue<>();

        private Connection wrap(Connection actual) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object result = invoke(actual, method, arguments);
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                        String sql = ((String) arguments[0]).replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
                        return wrapStatement(actual, statement, sql);
                    }
                    return result;
                });
        }

        private PreparedStatement wrapStatement(Connection actual, PreparedStatement statement, String sql)
            throws SQLException {
            statement.setQueryTimeout(5);
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    Object result = invoke(statement, method, arguments);
                    if (method.getName().equals("executeQuery") && result instanceof ResultSet rows) {
                        reads.add(evidence(actual, sql, rows.getMetaData().getColumnCount()));
                        boolean ranking = sql.contains(" from product ") && sql.contains("join `like`")
                            && sql.contains(" group by ");
                        if (ranking && paused.compareAndSet(false, true)) {
                            rankingExecuted.countDown();
                            try {
                                if (!allowRead.await(10, TimeUnit.SECONDS)) {
                                    throw new SQLException("좋아요 순위 읽기 재개 대기 시간 초과");
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new SQLException("좋아요 순위 읽기 대기 중단", exception);
                            }
                        }
                    }
                    return result;
                });
        }

        private ReadEvidence evidence(Connection actual, String sql, int columns) throws SQLException {
            // wrapper 바깥에서 실행하는 관찰 SQL이므로 업무 SELECT 개수에 포함하지 않는다.
            try (var statement = actual.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery("SELECT CONNECTION_ID(), @@transaction_isolation, @@autocommit")) {
                    if (!result.next()) {
                        throw new SQLException("좋아요 순위 스냅샷 트랜잭션 상태를 확인할 수 없습니다.");
                    }
                    return new ReadEvidence(sql, columns, result.getLong(1), result.getString(2), result.getBoolean(3),
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                }
            }
        }

        private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private record ReadEvidence(String sql, int columns, long connectionId, String isolation, boolean autoCommit,
                                boolean transactionActive, boolean readOnly) {
    }
}
