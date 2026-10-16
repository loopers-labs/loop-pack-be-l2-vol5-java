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
class ProductListCacheSnapshotIntegrationTest {

    @Autowired private ProductListReader reader;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp cleanup;
    @MockitoSpyBean(name = "mySqlMainDataSource") private HikariDataSource dataSource;

    private final ThreadLocal<Boolean> observeReader = ThreadLocal.withInitial(() -> false);
    private SnapshotReadGate gate;
    private JdbcTemplate jdbc;
    private Brand brand;
    private long productId;
    private long secondProductId;
    private boolean workersTerminated = true;

    @BeforeEach
    void setUp() throws SQLException {
        doAnswer(invocation -> {
            Connection actual = (Connection) invocation.callRealMethod();
            return observeReader.get() ? gate.wrap(actual) : actual;
        }).when(dataSource).getConnection();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        brand = brands.save(new Brand("캐시 스냅샷 브랜드"));
        productId = products.save(new Product(brand, "기존 상품", 100, 5)).getId();
        secondProductId = products.save(new Product(brand, "다른 상품", 200, 3)).getId();
        users.save(new User(42));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> revisions.advance());
    }

    @AfterEach
    void tearDown() {
        if (gate != null) {
            gate.allowRead.countDown();
        }
        assertThat(workersTerminated).as("조회 worker가 종료되지 않으면 DB를 정리하지 않는다").isTrue();
        cleanup.truncateAllTables();
        observeReader.remove();
    }

    @ParameterizedTest
    @EnumSource(ReadBoundary.class)
    @Timeout(45)
    void readsRevisionCountAndPayloadFromOneSnapshotAcrossACommittedMutation(ReadBoundary boundary) throws Exception {
        gate = new SnapshotReadGate(boundary);
        assertThat(AopUtils.isAopProxy(reader)).as("실제 Spring ProductListReader 프록시").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var executor = Executors.newSingleThreadExecutor();
        Future<ProductListSnapshot> reading = null;
        ProductListSnapshot duringChange;
        WriteEvidence write;
        workersTerminated = false;
        try {
            reading = executor.submit(() -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                observeReader.set(true);
                try {
                    return reader.readSnapshot();
                } finally {
                    observeReader.remove();
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                }
            });
            assertThat(gate.readExecuted.await(10, TimeUnit.SECONDS))
                .as("선택한 SELECT가 실제 실행된 뒤 다음 조회 전에 정지한다: %s", boundary).isTrue();

            TransactionTemplate writer = new TransactionTemplate(transactionManager);
            writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            writer.setTimeout(10);
            write = writer.execute(status -> {
                assertThat(status.isNewTransaction()).isTrue();
                Long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertThat(jdbc.update("UPDATE product SET price = ? WHERE id = ?", 500, productId)).isEqualTo(1);
                assertThat(jdbc.update("INSERT INTO `like` (user_id, product_id, created_at) "
                    + "VALUES (?, ?, CURRENT_TIMESTAMP(6))", 42, productId)).isEqualTo(1);
                long newProductId = products.save(new Product(brand, "추가 상품", 300, 7)).getId();
                revisions.advance();
                return new WriteEvidence(connectionId, newProductId);
            });
            // 실제 커밋과 독립 재조회를 확인한 뒤에만 진행 중 reader를 재개한다.
            assertThat(revisions.current()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, productId)).isEqualTo(500);
            gate.allowRead.countDown();
            duringChange = reading.get(10, TimeUnit.SECONDS);
        } finally {
            gate.allowRead.countDown();
            if (reading != null) {
                reading.cancel(true);
            }
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            assertThat(workersTerminated).isTrue();
        }

        assertThat(duringChange.revision()).isEqualTo(1);
        assertThat(duringChange.page().totalElements()).isEqualTo(2);
        assertThat(duringChange.page().totalPages()).isEqualTo(1);
        assertThat(duringChange.page().items()).extracting(ProductListSnapshot.Item::productId)
            .containsExactly(secondProductId, productId);
        assertThat(duringChange.page().items()).filteredOn(item -> item.productId() == productId)
            .singleElement().satisfies(item -> {
                assertThat(item.price()).isEqualTo(100);
                assertThat(item.likeCount()).isZero();
            });

        List<ReadEvidence> reads = List.copyOf(gate.reads);
        assertThat(reads).as("버전·전체 개수·ID/좋아요 순위·선정 상품 상세의 실제 SELECT").hasSize(4);
        assertThat(reads.getFirst().sql()).contains("from product_list_revision");
        assertThat(reads.get(1).sql()).startsWith("select count(");
        assertThat(reads.get(2).sql()).contains("from product ", "join `like`", "group by", "order by");
        assertThat(reads.getLast().sql()).contains("from product ", " in (")
            .doesNotContain("`like`", "group by");
        long readerConnectionId = reads.getFirst().connectionId();
        assertThat(write).isNotNull();
        assertThat(write.connectionId()).isNotEqualTo(readerConnectionId);
        assertThat(reads).allSatisfy(read -> {
            assertThat(read.connectionId()).isEqualTo(readerConnectionId);
            assertThat(read.isolation()).isEqualTo("REPEATABLE-READ");
            assertThat(read.autoCommit()).isFalse();
            assertThat(read.transactionActive()).isTrue();
            assertThat(read.readOnly()).isTrue();
            assertThat(read.sql()).doesNotContain("for update", "for share");
        });

        ProductListSnapshot afterChange = reader.readSnapshot();
        assertThat(afterChange.revision()).isEqualTo(2);
        assertThat(afterChange.page().totalElements()).isEqualTo(3);
        assertThat(afterChange.page().items()).extracting(ProductListSnapshot.Item::productId)
            .containsExactly(productId, write.productId(), secondProductId);
        assertThat(afterChange.page().items()).first().satisfies(item -> {
            assertThat(item.price()).isEqualTo(500);
            assertThat(item.likeCount()).isEqualTo(1);
        });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    enum ReadBoundary {
        REVISION, COUNT
    }

    private static final class SnapshotReadGate {
        private final ReadBoundary boundary;
        private final CountDownLatch readExecuted = new CountDownLatch(1);
        private final CountDownLatch allowRead = new CountDownLatch(1);
        private final AtomicBoolean paused = new AtomicBoolean();
        private final ConcurrentLinkedQueue<ReadEvidence> reads = new ConcurrentLinkedQueue<>();

        private SnapshotReadGate(ReadBoundary boundary) {
            this.boundary = boundary;
        }

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

        private PreparedStatement wrapStatement(Connection actual, PreparedStatement statement, String sql) throws SQLException {
            statement.setQueryTimeout(5);
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    Object result = invoke(statement, method, arguments);
                    if (method.getName().equals("executeQuery") && sql.startsWith("select")) {
                        reads.add(evidence(actual, sql));
                        boolean selected = boundary == ReadBoundary.REVISION
                            ? sql.contains("from product_list_revision") : sql.startsWith("select count(");
                        if (selected && paused.compareAndSet(false, true)) {
                            readExecuted.countDown();
                            try {
                                if (!allowRead.await(10, TimeUnit.SECONDS)) {
                                    throw new SQLException("캐시 스냅샷 읽기 재개 대기 시간 초과");
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new SQLException("캐시 스냅샷 읽기 대기 중단", exception);
                            }
                        }
                    }
                    return result;
                });
        }

        private ReadEvidence evidence(Connection actual, String sql) throws SQLException {
            try (var statement = actual.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery("SELECT CONNECTION_ID(), @@transaction_isolation, @@autocommit")) {
                    if (!result.next()) {
                        throw new SQLException("캐시 스냅샷 트랜잭션 상태를 확인할 수 없습니다.");
                    }
                    return new ReadEvidence(sql, result.getLong(1), result.getString(2), result.getBoolean(3),
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

    private record ReadEvidence(String sql, long connectionId, String isolation, boolean autoCommit,
                                boolean transactionActive, boolean readOnly) {
    }

    private record WriteEvidence(long connectionId, long productId) {
    }
}
