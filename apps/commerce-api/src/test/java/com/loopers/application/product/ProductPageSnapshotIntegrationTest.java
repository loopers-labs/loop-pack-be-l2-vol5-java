package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
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
class ProductPageSnapshotIntegrationTest {
    @Autowired private ProductQueryService queryService;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean(name = "mySqlMainDataSource") private HikariDataSource dataSource;

    private final ThreadLocal<Boolean> observeReader = ThreadLocal.withInitial(() -> false);
    private PageReadGate gate;
    private JdbcTemplate jdbc;
    private long productId;
    private boolean workersTerminated = true;

    @BeforeEach
    void setUp() throws SQLException {
        gate = new PageReadGate();
        doAnswer(invocation -> {
            Connection actual = (Connection) invocation.callRealMethod();
            return observeReader.get() ? gate.wrap(actual) : actual;
        }).when(dataSource).getConnection();
        // 공유 JdbcTemplate 빈의 timeout 설정을 바꾸지 않는다.
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        Brand brand = brands.save(new Brand("일관된 페이지 브랜드"));
        productId = products.save(new Product(brand, "일관된 상품", 100, 5)).getId();
        users.save(new User(42));
    }

    @AfterEach
    void tearDown() {
        gate.allowRead.countDown();
        assertThat(workersTerminated).as("조회 worker가 종료되지 않으면 DB를 정리하지 않는다").isTrue();
        cleanUp.truncateAllTables();
        observeReader.remove();
    }

    @ParameterizedTest
    @EnumSource(value = ProductSort.class, names = {"LATEST", "PRICE_ASC"})
    @Timeout(45)
    void keepsPriceAndLikesInOneSnapshotWhenAChangeCommitsBetweenPageAndLikeReads(ProductSort sort)
        throws Exception {
        assertThat(AopUtils.isAopProxy(queryService)).as("실제 Spring 읽기 트랜잭션 프록시").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var executor = Executors.newSingleThreadExecutor();
        Future<PageResult<ProductInfo>> reader = null;
        PageResult<ProductInfo> duringChange;
        Long writerConnectionId;
        workersTerminated = false;
        try {
            reader = executor.submit(() -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                observeReader.set(true);
                try {
                    // 외부 트랜잭션으로 감싸지 않고 실제 서비스 프록시의 기본 격리 수준을 사용한다.
                    return queryService.getList(null, 0, 20, sort);
                } finally {
                    observeReader.remove();
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                }
            });
            assertThat(gate.pageExecuted.await(10, TimeUnit.SECONDS))
                .as("상품 페이지 SELECT가 실제 실행되어 결과를 돌려주기 직전 대기한다").isTrue();

            TransactionTemplate writer = new TransactionTemplate(transactionManager);
            writer.setTimeout(10);
            writerConnectionId = writer.execute(status -> {
                assertThat(status.isNewTransaction()).isTrue();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                Long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertThat(jdbc.update("UPDATE product SET price = ? WHERE id = ?", 500, productId)).isEqualTo(1);
                assertThat(jdbc.update("INSERT INTO `like` (user_id, product_id, created_at) "
                    + "VALUES (?, ?, CURRENT_TIMESTAMP(6))", 42, productId)).isEqualTo(1);
                return connectionId;
            });
            // execute의 정상 반환으로 다른 연결의 두 변경이 함께 commit된 뒤에만 조회를 재개한다.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, productId))
                .isEqualTo(500L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like` WHERE product_id = ?", Long.class, productId))
                .isEqualTo(1L);
            gate.allowRead.countDown();
            duringChange = reader.get(10, TimeUnit.SECONDS);
        } finally {
            gate.allowRead.countDown();
            if (reader != null) {
                reader.cancel(true);
            }
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            assertThat(workersTerminated).as("최종 응답 확인·DB 정리 전에 조회 worker 종료").isTrue();
        }

        assertPage(duringChange, 100, 0);
        List<ReadEvidence> reads = List.copyOf(gate.reads);
        assertThat(reads).as("count·상품 페이지·일괄 좋아요 집계를 같은 트랜잭션에서 읽었다").hasSize(3);
        assertThat(reads.get(1).sql()).doesNotContain("`like`");
        assertThat(reads.getLast().sql()).contains("from `like`", " in (", " group by ");
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
        assertPage(queryService.getList(null, 0, 20, sort), 500, 1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void assertPage(PageResult<ProductInfo> page, long price, long likeCount) {
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(product -> {
            assertThat(product.productId()).isEqualTo(productId);
            assertThat(product.price()).isEqualTo(price);
            assertThat(product.likeCount()).isEqualTo(likeCount);
        });
    }

    /** 실제 SQL 결과는 바꾸지 않고 페이지 SELECT 반환과 다음 집계 사이만 통제한다. */
    private static final class PageReadGate {
        private final CountDownLatch pageExecuted = new CountDownLatch(1);
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
                    if (method.getName().equals("executeQuery") && sql.startsWith("select")) {
                        reads.add(evidence(actual, sql));
                        boolean productPage = sql.contains(" from product ") && !sql.startsWith("select count(");
                        if (productPage && paused.compareAndSet(false, true)) {
                            pageExecuted.countDown();
                            try {
                                if (!allowRead.await(10, TimeUnit.SECONDS)) {
                                    throw new SQLException("상품 페이지 읽기 재개 대기 시간 초과");
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new SQLException("상품 페이지 읽기 대기 중단", exception);
                            }
                        }
                    }
                    return result;
                });
        }

        private ReadEvidence evidence(Connection actual, String sql) throws SQLException {
            // wrapper를 거치지 않는 관찰용 SELECT이며 상품·좋아요 결과를 대신 만들지 않는다.
            try (var statement = actual.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery("SELECT CONNECTION_ID(), @@transaction_isolation, @@autocommit")) {
                    if (!result.next()) {
                        throw new SQLException("읽기 트랜잭션 상태를 확인할 수 없습니다.");
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
}
