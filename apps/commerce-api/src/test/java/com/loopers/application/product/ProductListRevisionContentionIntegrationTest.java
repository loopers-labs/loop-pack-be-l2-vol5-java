package com.loopers.application.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductListRevisionContentionIntegrationTest {
    @Autowired private AdminProductService service;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp cleanup;
    @MockitoSpyBean(name = "mySqlMainDataSource") private HikariDataSource dataSource;

    private final ThreadLocal<Boolean> observeWriter = ThreadLocal.withInitial(() -> false);
    private final CountDownLatch firstReady = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final CountDownLatch revisionExecuting = new CountDownLatch(1);
    private JdbcTemplate jdbc;
    private long firstId;
    private long secondId;
    private volatile long observedConnectionId;
    private volatile long revisionNanos;
    private boolean workersTerminated = true;

    @BeforeEach
    void setUp() throws SQLException {
        doAnswer(invocation -> {
            Connection connection = (Connection) invocation.callRealMethod();
            return observeWriter.get() ? wrap(connection) : connection;
        }).when(dataSource).getConnection();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        firstId = products.save(new Product(brands.save(new Brand("첫 브랜드")), "첫 상품", 100, 5)).getId();
        secondId = products.save(new Product(brands.save(new Brand("둘째 브랜드")), "둘째 상품", 200, 5)).getId();
        // 운영 DDL과 같은 초기 metadata 행을 준비하고 실제 서비스 경로만 경합시킨다.
        jdbc.update("INSERT INTO product_list_revision (id, revision) VALUES (1, 0)");
    }

    @AfterEach
    void tearDown() {
        releaseFirst.countDown();
        observeWriter.remove();
        assertThat(workersTerminated).as("모든 writer가 종료된 뒤 DB를 정리한다").isTrue();
        cleanup.truncateAllTables();
    }

    @Test
    @Timeout(30)
    void differentBrandProductWritersWaitAtTheSharedRevisionThenBothCommit(TestReporter reporter) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        workersTerminated = false;
        Future<WriteResult> first = null;
        Future<WriteResult> second = null;
        WriteResult firstResult;
        WriteResult secondResult;
        try {
            first = executor.submit(() -> {
                long started = System.nanoTime();
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.setTimeout(10);
                Long connectionId = transaction.execute(status -> {
                    long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    assertThat(service.changeStock(UserRole.ADMIN, firstId, 7).stockQuantity()).isEqualTo(7);
                    assertThat(revisions.current()).isEqualTo(1);
                    firstReady.countDown();
                    await(releaseFirst);
                    return connection;
                });
                return new WriteResult(connectionId, System.nanoTime() - started);
            });
            assertThat(firstReady.await(5, TimeUnit.SECONDS)).isTrue();
            second = executor.submit(() -> {
                observeWriter.set(true);
                long started = System.nanoTime();
                try {
                    assertThat(service.changeStock(UserRole.ADMIN, secondId, 9).stockQuantity()).isEqualTo(9);
                    return new WriteResult(observedConnectionId, System.nanoTime() - started);
                } finally {
                    observeWriter.remove();
                }
            });
            assertThat(revisionExecuting.await(5, TimeUnit.SECONDS)).isTrue();
            Future<WriteResult> waiting = second;
            assertThatThrownBy(() -> waiting.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            // 별도 연결에서는 아직 두 변경이 모두 미커밋 상태다.
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class, firstId))
                .isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class, secondId))
                .isEqualTo(5);
            assertThat(revisions.current()).isZero();
            releaseFirst.countDown();
            firstResult = first.get(5, TimeUnit.SECONDS);
            secondResult = second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            if (first != null) {
                first.cancel(true);
            }
            if (second != null) {
                second.cancel(true);
            }
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(firstResult.connectionId()).isNotEqualTo(secondResult.connectionId());
        assertThat(products.findById(firstId).orElseThrow().getStockQuantity()).isEqualTo(7);
        assertThat(products.findById(secondId).orElseThrow().getStockQuantity()).isEqualTo(9);
        assertThat(revisions.current()).isEqualTo(2);
        assertThat(TimeUnit.NANOSECONDS.toMillis(revisionNanos)).isGreaterThanOrEqualTo(200);
        Map<String, Object> evidence = Map.of("scenario", "different products, deliberately held shared revision lock",
            "successfulServiceCalls", 2, "finalRevision", 2, "minimumForcedHoldMs", 200,
            "firstTransactionMs", firstResult.elapsedNanos() / 1_000_000.0,
            "secondServiceMs", secondResult.elapsedNanos() / 1_000_000.0,
            "secondRevisionSqlMs", revisionNanos / 1_000_000.0,
            "measuredAt", Instant.now().toString(),
            "limitation", "Controlled lock-hold evidence, not production latency or throughput");
        Path destination = Path.of("build", "reports", "week4", "product-list-revision-contention.json");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        reporter.publishEntry(Map.of("scenario", "different products, deliberately held shared revision lock",
            "successful_service_calls", "2", "final_revision", "2", "minimum_forced_hold_ms", "200",
            "first_transaction_ms", millis(firstResult.elapsedNanos()),
            "second_service_ms", millis(secondResult.elapsedNanos()),
            "second_revision_sql_ms", millis(revisionNanos)));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return identity(proxy, method, arguments, connection);
                }
                if (isOwnWrapper(proxy, method, arguments)) {
                    return method.getName().equals("unwrap") ? proxy : true;
                }
                Object result = invoke(connection, method, arguments);
                if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement
                    && arguments != null && arguments.length > 0 && arguments[0] instanceof String sql
                    && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("insert into product_list_revision")) {
                    return wrapRevision(statement, connection);
                }
                return result;
            });
    }

    private PreparedStatement wrapRevision(PreparedStatement statement, Connection connection) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return identity(proxy, method, arguments, statement);
                }
                if (isOwnWrapper(proxy, method, arguments)) {
                    return method.getName().equals("unwrap") ? proxy : true;
                }
                if (method.getName().equals("executeUpdate")) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(connection.getAutoCommit()).isFalse();
                    assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                    assertThat(readLong(connection, "SELECT stock_quantity FROM product WHERE id = " + secondId))
                        .as("별도 상품의 실제 UPDATE가 공통 버전 SQL보다 먼저 완료됐다").isEqualTo(9);
                    observedConnectionId = readLong(connection, "SELECT CONNECTION_ID()");
                    long started = System.nanoTime();
                    revisionExecuting.countDown();
                    try {
                        return invoke(statement, method, arguments);
                    } finally {
                        revisionNanos = System.nanoTime() - started;
                    }
                }
                return invoke(statement, method, arguments);
            });
    }

    private static long readLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static boolean isOwnWrapper(Object proxy, Method method, Object[] arguments) {
        return (method.getName().equals("unwrap") || method.getName().equals("isWrapperFor"))
            && arguments != null && arguments.length == 1 && arguments[0] instanceof Class<?> type && type.isInstance(proxy);
    }

    private static Object identity(Object proxy, Method method, Object[] arguments, Object delegate) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> delegate.toString();
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the revision transaction", exception);
        }
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private record WriteResult(long connectionId, long elapsedNanos) {
    }
}
