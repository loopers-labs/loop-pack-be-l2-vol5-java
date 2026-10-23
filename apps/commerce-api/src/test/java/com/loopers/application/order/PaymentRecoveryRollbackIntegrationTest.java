package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/** HTTP TX B failure after a real SQL write, with an independent committed-state observer connection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(25)
class PaymentRecoveryRollbackIntegrationTest {
    private static final LocalTestPg PG = createPg();
    private static final String CARD = "0000-0000-0000-0000";
    private static final Map<String, String> STATE_QUERIES = Map.of(
        "orders", "SELECT * FROM `order` ORDER BY id",
        "items", "SELECT * FROM order_item ORDER BY id",
        "attempts", "SELECT id,order_id,HEX(attempt_key),provider_account,provider_order_id,amount,status,send_started_at,"
            + "transaction_key,HEX(request_fingerprint),fingerprint_version,key_version FROM payment_attempt ORDER BY id",
        "products", "SELECT * FROM product ORDER BY id",
        "users", "SELECT * FROM user ORDER BY id",
        "brands", "SELECT * FROM brand ORDER BY id",
        "revision", "SELECT * FROM product_list_revision ORDER BY id");

    @Autowired private TestRestTemplate rest;
    @Autowired private OrderService orders;
    @Autowired private OrderPaymentCoordinator coordinator;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean(name = "mySqlMainDataSource") private HikariDataSource dataSource;

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    private final WriteProbe probe = new WriteProbe();

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("commerce.test-pg.enabled", () -> true);
        registry.add("commerce.test-pg.base-url", () -> PG.baseUri().toString());
        registry.add("commerce.test-pg.callback-url", () -> PG.callbackUri().toString());
    }

    @BeforeEach
    void setUp() throws SQLException {
        PG.reset();
        doAnswer(invocation -> probe.wrap((Connection) invocation.callRealMethod())).when(dataSource).getConnection();
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id IN (1,2)");
    }

    @AfterEach
    void tearDown() {
        probe.disarm();
        PG.releaseResponses();
        cleanup.truncateAllTables();
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,true", "SUCCESS,false", "FAILED,true", "FAILED,false"})
    void realSqlFailureRollsBackEveryLocalFactAndTheOtherRecoveryEntryCanApplyTheExternalResultOnce(
        String externalResult, boolean callbackFirst) throws Exception {
        Fixture fixture = pending();
        PG.setStatus("TEST-TX-1", externalResult);
        var before = state();
        long revision = revision();

        // Reserve another physical connection before the HTTP request; it cannot be the TX B writer connection.
        try (Connection observer = dataSource.getConnection()) {
            assertThat(observer.getAutoCommit()).isTrue();
            long observerId = connectionId(observer);
            probe.arm();
            ResponseEntity<JsonNode> failed;
            try {
                failed = callbackFirst ? callback(fixture, externalResult) : reconcile(fixture);
            } finally {
                probe.disarm();
            }

            failure(failed, 500, "INTERNAL_ERROR");
            assertThat(failed.getBody().toString()).doesNotContain("test-only", "SQLException", "update ", CARD);
            WriteEvidence evidence = probe.evidence();
            assertThat(evidence.completedWrites()).isEqualTo(1);
            assertThat(evidence.failures()).isEqualTo(1);
            assertThat(evidence.executedSql()).hasSize(1);
            assertThat(evidence.failedSql()).isNotBlank();
            assertThat(evidence.transactionActive()).isTrue();
            assertThat(evidence.autoCommit()).isFalse();
            assertThat(evidence.connectionId()).isNotEqualTo(observerId);
            assertThat(evidence.changedState()).as("Actual first SQL changed rows inside TX B, not just dirty entities")
                .isNotEqualTo(before);
            assertThat(evidence.changedState().get("users")).isEqualTo(before.get("users"));
            assertThat(evidence.changedState().get("brands")).isEqualTo(before.get("brands"));
            assertThat(evidence.changedState().get("revision")).isEqualTo(before.get("revision"));
            assertThat(readState(observer)).as("Independent DB connection sees full rollback after HTTP returns")
                .isEqualTo(before);
        }

        assertThat(state()).isEqualTo(before);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(success(get(fixture), 200).path("payment").path("status").asText()).isEqualTo("PENDING");
        assertThat(PG.lookupCalls()).as("Stored order GET never re-observes PG").isEqualTo(1);

        if (callbackFirst) {
            assertThat(success(reconcile(fixture), 200).path("payment").path("status").asText()).isEqualTo(externalResult);
        } else {
            acknowledged(callback(fixture, externalResult));
        }
        assertFinalState(fixture, externalResult);
        assertThat(revision()).isEqualTo(revision + (externalResult.equals("FAILED") ? 1 : 0));
        assertThat(state().get("users")).isEqualTo(before.get("users"));
        assertThat(state().get("brands")).isEqualTo(before.get("brands"));
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class,
            fixture.unrelatedProductId())).isEqualTo(8);
        var committed = state();

        acknowledged(callback(fixture, externalResult));
        assertThat(success(reconcile(fixture), 200).path("payment").path("status").asText()).isEqualTo(externalResult);
        assertThat(state()).isEqualTo(committed);
        assertThat(PG.lookupCalls()).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    private Fixture pending() {
        Product unrelated = products.save(new Product(brands.save(new Brand("보존 브랜드")), "보존 상품", 99, 8));
        Product product = products.save(new Product(brands.save(new Brand("복구 브랜드")), "복구 상품", 100, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        String providerOrderId = ProviderOrderId.resolve(orderId, null);
        coordinator.startOrReuse("alice", orderId, UUID.randomUUID(), providerOrderId, "TEST", CARD);
        return new Fixture(orderId, product.getId(), unrelated.getId(), providerOrderId);
    }

    private ResponseEntity<JsonNode> reconcile(Fixture fixture) {
        return request(HttpMethod.POST, "/api/v1/orders/" + fixture.orderId() + "/payment/reconcile", "alice", "{}");
    }

    private ResponseEntity<JsonNode> callback(Fixture fixture, String hint) {
        return request(HttpMethod.POST, "/api/v1/payments/callback", null,
            "{\"providerOrderId\":\"" + fixture.providerOrderId() + "\",\"transactionKey\":\"TEST-TX-1\",\"status\":\""
                + hint + "\"}");
    }

    private ResponseEntity<JsonNode> get(Fixture fixture) {
        return request(HttpMethod.GET, "/api/v1/orders/" + fixture.orderId(), "alice", null);
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode success(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private void acknowledged(ResponseEntity<JsonNode> response) {
        assertThat(success(response, 200).path("acknowledged").asBoolean()).isTrue();
    }

    private void failure(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private void assertFinalState(Fixture fixture, String result) {
        boolean success = result.equals("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo(success ? "CONFIRMED" : "PAYMENT_FAILED");
        assertThat(jdbc.queryForObject("SELECT paid_amount FROM `order` WHERE id=?", Long.class, fixture.orderId()))
            .isEqualTo(success ? 200L : null);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class,
            fixture.orderId())).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class,
            fixture.orderId())).isEqualTo(success ? "CONSUMED" : "RELEASED");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId()))
            .isEqualTo(success ? 3 : 5);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class)).isEqualTo(1);
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id=1", Long.class);
    }

    private Map<String, List<Map<String, Object>>> state() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        STATE_QUERIES.forEach((name, sql) -> result.put(name, jdbc.queryForList(sql)));
        return result;
    }

    private static Map<String, List<Map<String, Object>>> readState(Connection connection) throws SQLException {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (var query : STATE_QUERIES.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query.getValue());
                 ResultSet data = statement.executeQuery()) {
                while (data.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= data.getMetaData().getColumnCount(); column++) {
                        row.put(data.getMetaData().getColumnLabel(column), JdbcUtils.getResultSetValue(data, column));
                    }
                    rows.add(Collections.unmodifiableMap(row));
                }
            }
            result.put(query.getKey(), List.copyOf(rows));
        }
        return result;
    }

    private static long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static LocalTestPg createPg() {
        try {
            return new LocalTestPg();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start isolated local PG", exception);
        }
    }

    private record Fixture(long orderId, long productId, long unrelatedProductId, String providerOrderId) { }

    private record WriteEvidence(int completedWrites, int failures, List<String> executedSql, String failedSql,
                                 boolean transactionActive, boolean autoCommit, long connectionId,
                                 Map<String, List<Map<String, Object>>> changedState) { }

    private static final class WriteProbe {
        private static final Pattern WRITE = Pattern.compile(
            "^update\\s+(?:[\\w`]+\\.)?`?(order|order_item|product|payment_attempt)`?\\s+", Pattern.CASE_INSENSITIVE);
        private boolean armed;
        private int completedWrites;
        private int failures;
        private final List<String> executedSql = new ArrayList<>();
        private String failedSql;
        private boolean transactionActive;
        private boolean autoCommit;
        private long writerConnectionId;
        private Map<String, List<Map<String, Object>>> changedState = Map.of();

        synchronized void arm() {
            armed = true;
            completedWrites = 0;
            failures = 0;
            executedSql.clear();
            changedState = Map.of();
        }

        synchronized void disarm() { armed = false; }

        synchronized WriteEvidence evidence() {
            return new WriteEvidence(completedWrites, failures, List.copyOf(executedSql), failedSql,
                transactionActive, autoCommit, writerConnectionId, changedState);
        }

        Connection wrap(Connection raw) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(raw, method, arguments);
                    if (result instanceof Statement statement) {
                        String sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String value
                            ? value : null;
                        return wrapStatement(raw, statement, sql);
                    }
                    return result;
                });
        }

        private Statement wrapStatement(Connection raw, Statement target, String preparedSql) {
            Class<?> type = target instanceof CallableStatement ? CallableStatement.class
                : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
                String sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String value
                    ? value : preparedSql;
                String name = method.getName();
                if ((name.equals("executeUpdate") || name.equals("executeLargeUpdate") || name.equals("execute"))
                    && sql != null && WRITE.matcher(sql.stripLeading()).find()) {
                    return write(raw, target, method, arguments, sql);
                }
                return invoke(target, method, arguments);
            });
        }

        private synchronized Object write(Connection raw, Statement target, Method method, Object[] arguments,
                                           String sql) throws Throwable {
            if (!armed) {
                return invoke(target, method, arguments);
            }
            if (completedWrites == 1) {
                transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
                autoCommit = raw.getAutoCommit();
                writerConnectionId = connectionId(raw);
                changedState = readState(raw);
                failedSql = sql;
                failures++;
                armed = false;
                throw new SQLException("test-only recovery write failure after real SQL", "HY000", 0);
            }
            Object result = invoke(target, method, arguments);
            long affected = result instanceof Number number ? number.longValue() : target.getLargeUpdateCount();
            if (affected <= 0) {
                throw new SQLException("test-only recovery probe requires an actual changed row", "HY000", 0);
            }
            completedWrites++;
            executedSql.add(sql);
            return result;
        }

        private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
