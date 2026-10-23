package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.AdminProductService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.payment.CircuitBreakingPaymentGateway;
import com.loopers.infrastructure.payment.HttpTestPgClient;
import com.loopers.infrastructure.payment.PgHttpTimeouts;
import com.loopers.infrastructure.payment.PgLookupCircuit;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real recovery HTTP, isolated MySQL, real local PG HTTP; response gates force overlapping observations. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(30)
class PaymentRecoveryConcurrencyIntegrationTest {
    private static final String CARD = "0000-0000-0000-0000";
    private static final LocalTestPg PG = createPg();
    private static BoundaryGateway managedGateway;

    @Autowired private TestRestTemplate rest;
    @Autowired private OrderService orders;
    @Autowired private OrderPaymentCoordinator coordinator;
    @Autowired private OrderPaymentStockService stocks;
    @Autowired private AdminProductService adminProducts;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;
    @TestBean
    private PaymentGateway paymentGateway;
    @TestBean
    private PaymentConfirmationTiming paymentConfirmationTiming;

    private boolean workersTerminated;

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("commerce.test-pg.enabled", () -> true);
        registry.add("commerce.test-pg.base-url", () -> PG.baseUri().toString());
        registry.add("commerce.test-pg.callback-url", () -> PG.callbackUri().toString());
    }

    static PaymentGateway paymentGateway() {
        PgHttpTimeouts defaults = PgHttpTimeouts.defaults();
        var client = new HttpTestPgClient(PG.baseUri(), PG.callbackUri(), new ObjectMapper(),
            new PgHttpTimeouts(defaults.connect(), defaults.pool(), defaults.createResponse(), defaults.lookupResponse(),
                Duration.ofSeconds(3), defaults.lookupTotal()));
        managedGateway = new BoundaryGateway(new CircuitBreakingPaymentGateway(client, PgLookupCircuit.create()));
        return managedGateway;
    }

    static PaymentConfirmationTiming paymentConfirmationTiming() {
        return new PaymentConfirmationTiming() {
            private final ThreadLocal<Long> nanos = ThreadLocal.withInitial(() -> 0L);

            @Override
            public long nanoTime() { return nanos.get(); }

            @Override
            public void sleep(Duration duration) { nanos.set(nanos.get() + duration.toNanos()); }

            @Override
            public double jitterFactor() { return 1; }
        };
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        managedGateway.reset();
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        workersTerminated = true;
    }

    @AfterEach
    void tearDown() {
        managedGateway.release();
        PG.releaseResponses();
        assertThat(workersTerminated).as("All HTTP request workers end before test DB cleanup").isTrue();
        cleanup.truncateAllTables();
    }

    @AfterAll
    static void closePg() throws Exception {
        managedGateway.close();
        PG.close();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,true", "SUCCESS,false", "FAILED,true", "FAILED,false"})
    void callbackFirstAndReconcileFirstReachTheSameFinalFactsWithoutDuplicateEffects(String result, boolean callbackFirst) {
        Fixture fixture = pending();
        PG.setStatus(fixture.transactionKey(), result);
        long revision = revision();

        if (callbackFirst) {
            acknowledged(callback(fixture, "PENDING"));
            assertPayment(reconcile(fixture), result);
            assertThat(PG.lookupCalls()).isEqualTo(1); // Terminal reconcile does not even query PG.
        } else {
            assertPayment(reconcile(fixture), result);
            acknowledged(callback(fixture, "PENDING"));
            assertThat(PG.lookupCalls()).isEqualTo(2); // Callback still verifies its transaction identity authoritatively.
        }

        assertFinalState(fixture, result, result.equals("SUCCESS") ? 3 : 5);
        assertThat(revision()).isEqualTo(revision + (result.equals("FAILED") ? 1 : 0));
        Map<String, List<Map<String, Object>>> terminal = state();
        acknowledged(callback(fixture, result));
        assertPayment(reconcile(fixture), result);
        assertThat(state()).isEqualTo(terminal);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void concurrentCallbackAndReconcileBothObservePgBeforeCompetingForTheSameResult(String result) throws Exception {
        Fixture fixture = pending();
        PG.setStatus(fixture.transactionKey(), result);
        long revision = revision();
        Gate gate = managedGateway.holdLookupResults(2);
        ExecutorService workers = workers();
        try {
            var callback = workers.submit(() -> callback(fixture, "PENDING"));
            var reconcile = workers.submit(() -> reconcile(fixture));
            assertThat(gate.entered.await(3, TimeUnit.SECONDS)).as("Both real GETs returned before either TX B").isTrue();
            assertThat(callback.isDone()).isFalse();
            assertThat(reconcile.isDone()).isFalse();
            assertThat(PG.lookupCalls()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo("PENDING");
            gate.release.countDown();

            acknowledged(callback.get(5, TimeUnit.SECONDS));
            assertPayment(reconcile.get(5, TimeUnit.SECONDS), result);
            assertFinalState(fixture, result, result.equals("SUCCESS") ? 3 : 5);
            assertThat(revision()).isEqualTo(revision + (result.equals("FAILED") ? 1 : 0));
            assertThat(managedGateway.lookupBoundaries).hasValue(2);
            assertThat(PG.createCalls()).isEqualTo(1);
            assertThat(PG.transactionCount()).isEqualTo(1);
        } finally {
            gate.release.countDown();
            finish(workers);
        }
    }

    @Test
    void twoConcurrentFailedCallbacksReturnReservationOnlyOnce() throws Exception {
        Fixture fixture = pending();
        PG.setStatus(fixture.transactionKey(), "FAILED");
        long revision = revision();
        Gate gate = managedGateway.holdLookupResults(2);
        ExecutorService workers = workers();
        try {
            var first = workers.submit(() -> callback(fixture, "FAILED"));
            var second = workers.submit(() -> callback(fixture, "FAILED"));
            assertThat(gate.entered.await(3, TimeUnit.SECONDS)).isTrue();
            gate.release.countDown();
            acknowledged(first.get(5, TimeUnit.SECONDS));
            acknowledged(second.get(5, TimeUnit.SECONDS));
            assertFinalState(fixture, "FAILED", 5);
            assertThat(revision()).isEqualTo(revision + 1);
            assertThat(PG.lookupCalls()).isEqualTo(2);
            assertThat(PG.createCalls()).isEqualTo(1);
        } finally {
            gate.release.countDown();
            finish(workers);
        }
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,false", "FAILED,false", "SUCCESS,true", "FAILED,true"})
    void callbackCompletesWhileFirstCreateIsHeldAndLatePendingOrTimeoutCannotRegressIt(String result,
                                                                                       boolean timeout) throws Exception {
        Fixture fixture = draft();
        PG.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
        ExecutorService workers = workers();
        try {
            var firstConfirm = workers.submit(() -> confirm(fixture));
            assertThat(PG.awaitCreate(Duration.ofSeconds(2))).isTrue();
            assertThat(firstConfirm.isDone()).isFalse();
            PG.setStatus(fixture.transactionKey(), result);
            acknowledged(callback(fixture, "PENDING"));
            Map<String, List<Map<String, Object>>> committed = state();
            if (!timeout) {
                PG.releaseResponses();
            }
            assertPayment(firstConfirm.get(5, TimeUnit.SECONDS), result);
            assertThat(managedGateway.lastCreateKind.get()).isEqualTo(timeout
                ? PaymentGatewayResult.Kind.UNKNOWN : PaymentGatewayResult.Kind.OBSERVED);
            assertThat(state()).isEqualTo(committed);
            assertFinalState(fixture, result, result.equals("SUCCESS") ? 3 : 5);
            assertThat(PG.createCalls()).isEqualTo(1);
            assertThat(PG.lookupCalls()).isEqualTo(1);
        } finally {
            PG.releaseResponses();
            finish(workers);
        }
    }

    @Test
    void externalRecoveryLookupHoldsNeitherOrderNorProductLocks() throws Exception {
        Fixture fixture = pending();
        PG.setStatus(fixture.transactionKey(), "FAILED");
        PG.replyNextHeld(200, transactionJson(fixture, "FAILED"));
        ExecutorService workers = workers();
        try {
            var recovery = workers.submit(() -> callback(fixture, "FAILED"));
            assertThat(PG.awaitRequest(Duration.ofSeconds(2))).isTrue();
            assertThat(recovery.isDone()).isFalse();
            var administrator = workers.submit(() -> adminProducts.changeStock(UserRole.ADMIN, fixture.productId(), 10));
            administrator.get(2, TimeUnit.SECONDS);
            var orderLock = workers.submit(() -> new TransactionTemplate(transactionManager).execute(status ->
                jdbc.queryForObject("SELECT id FROM `order` WHERE id=? FOR UPDATE", Long.class, fixture.orderId())));
            assertThat(orderLock.get(2, TimeUnit.SECONDS)).isEqualTo(fixture.orderId());
            assertThat(recovery.isDone()).as("DB lock work finished before releasing PG HTTP").isFalse();
            PG.releaseResponses();
            acknowledged(recovery.get(4, TimeUnit.SECONDS));
            assertFinalState(fixture, "FAILED", 12); // Return adds to current stock, never overwrites with old 5.
            assertThat(PG.lookupCalls()).isEqualTo(1);
            assertThat(managedGateway.lookupBoundaries).hasValue(1);
        } finally {
            PG.releaseResponses();
            finish(workers);
        }
    }

    @Test
    void callbackRechecksTheTransactionBindingWhenAnotherObservationLinksAKeyDuringItsHttpCall() throws Exception {
        Fixture fixture = draft();
        stocks.prepareNew("alice", fixture.orderId(), fixture.key(), "135135", fixture.providerOrderId(), "TEST", CARD);
        var first = paymentGateway.create(new PaymentCreateRequest(fixture.providerOrderId(), 200, "TEST", CARD))
            .transactions().getFirst();
        var second = paymentGateway.create(new PaymentCreateRequest(fixture.providerOrderId(), 200, "TEST", CARD))
            .transactions().getFirst();
        Gate gate = managedGateway.holdLookupResults(1);
        ExecutorService workers = workers();
        try {
            var callback = workers.submit(() -> callback(fixture, "PENDING"));
            assertThat(gate.entered.await(3, TimeUnit.SECONDS)).isTrue();
            stocks.apply(fixture.orderId(), fixture.key(), second);
            var linked = state();
            gate.release.countDown();
            failure(callback.get(4, TimeUnit.SECONDS), 409, "PAYMENT_RESULT_CONFLICT");
            assertThat(state()).isEqualTo(linked);
            assertThat(jdbc.queryForObject("SELECT transaction_key FROM payment_attempt WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo(second.transactionKey()).isNotEqualTo(first.transactionKey());
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class,
                fixture.productId())).isEqualTo(3);
            assertThat(PG.createCalls()).as("Only the two explicit fixture creates, no recovery create").isEqualTo(2);
            assertThat(PG.lookupCalls()).isEqualTo(1);
        } finally {
            gate.release.countDown();
            finish(workers);
        }
    }

    private Fixture draft() {
        Product product = products.save(new Product(brands.save(new Brand("복구 브랜드")), "복구 상품", 100, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(orderId, product.getId(), UUID.randomUUID(), ProviderOrderId.resolve(orderId, null), "TEST-TX-1");
    }

    private Fixture pending() {
        Fixture fixture = draft();
        coordinator.startOrReuse("alice", fixture.orderId(), fixture.key(), fixture.providerOrderId(), "TEST", CARD);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isZero();
        return fixture;
    }

    private ResponseEntity<JsonNode> confirm(Fixture fixture) {
        return request("/api/v1/orders/" + fixture.orderId() + "/confirm", "alice",
            "{\"paymentAttemptKey\":\"" + fixture.key() + "\",\"cardType\":\"TEST\",\"cardNo\":\"" + CARD + "\"}");
    }

    private ResponseEntity<JsonNode> reconcile(Fixture fixture) {
        return request("/api/v1/orders/" + fixture.orderId() + "/payment/reconcile", "alice", null);
    }

    private ResponseEntity<JsonNode> callback(Fixture fixture, String hint) {
        return request("/api/v1/payments/callback", null, "{\"providerOrderId\":\"" + fixture.providerOrderId()
            + "\",\"transactionKey\":\"" + fixture.transactionKey() + "\",\"status\":\"" + hint + "\"}");
    }

    private ResponseEntity<JsonNode> request(String path, String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void acknowledged(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("data").path("acknowledged").asBoolean()).isTrue();
    }

    private void assertPayment(ResponseEntity<JsonNode> response, String status) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("data").path("payment").path("status").asText()).isEqualTo(status);
        assertThat(response.getBody().path("data").path("paymentMethod").asText()).isEqualTo("PG");
    }

    private void failure(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private void assertFinalState(Fixture fixture, String status, int stock) {
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo(status.equals("SUCCESS") ? "CONFIRMED" : "PAYMENT_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class,
            fixture.orderId())).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class,
            fixture.orderId())).isEqualTo(status.equals("SUCCESS") ? "CONSUMED" : "RELEASED");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class,
            fixture.productId())).isEqualTo(stock);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class)).isEqualTo(1);
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id=1", Long.class);
    }

    private Map<String, List<Map<String, Object>>> state() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT id,order_id,HEX(attempt_key),provider_account,provider_order_id,"
                + "amount,status,send_started_at,transaction_key,HEX(request_fingerprint),fingerprint_version,key_version"
                + " FROM payment_attempt ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "revision", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private String transactionJson(Fixture fixture, String status) {
        return "{\"transactionKey\":\"" + fixture.transactionKey() + "\",\"orderId\":\"" + fixture.providerOrderId()
            + "\",\"amount\":200,\"status\":\"" + status + "\"}";
    }

    private ExecutorService workers() {
        workersTerminated = false;
        return Executors.newFixedThreadPool(3);
    }

    private void finish(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        workersTerminated = workers.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(workersTerminated).isTrue();
    }

    private static LocalTestPg createPg() {
        try {
            return new LocalTestPg();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start isolated local PG", exception);
        }
    }

    private record Fixture(long orderId, long productId, UUID key, String providerOrderId, String transactionKey) { }

    private static final class Gate {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);

        private Gate(int count) { entered = new CountDownLatch(count); }
    }

    private static final class BoundaryGateway implements PaymentGateway, AutoCloseable {
        private final CircuitBreakingPaymentGateway delegate;
        private final AtomicInteger lookupBoundaries = new AtomicInteger();
        private final AtomicReference<PaymentGatewayResult.Kind> lastCreateKind = new AtomicReference<>();
        private volatile Gate gate;

        private BoundaryGateway(CircuitBreakingPaymentGateway delegate) { this.delegate = delegate; }

        @Override
        public PaymentGatewayResult create(PaymentCreateRequest request) {
            outsideTransaction();
            var result = delegate.create(request);
            lastCreateKind.set(result.kind());
            return result;
        }

        @Override
        public PaymentGatewayResult findByTransactionKey(String transactionKey) {
            outsideTransaction();
            return observed(delegate.findByTransactionKey(transactionKey));
        }

        @Override
        public PaymentGatewayResult findByProviderOrderId(String providerOrderId) {
            outsideTransaction();
            return observed(delegate.findByProviderOrderId(providerOrderId));
        }

        private PaymentGatewayResult observed(PaymentGatewayResult result) {
            lookupBoundaries.incrementAndGet();
            Gate current = gate;
            if (current != null) {
                current.entered.countDown();
                try {
                    if (!current.release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Recovery result gate timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Recovery result gate interrupted", exception);
                }
            }
            return result;
        }

        private static void outsideTransaction() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        }

        private Gate holdLookupResults(int count) {
            Gate current = new Gate(count);
            gate = current;
            return current;
        }

        private void release() {
            if (gate != null) {
                gate.release.countDown();
            }
        }

        private void reset() {
            release();
            gate = null;
            lookupBoundaries.set(0);
            lastCreateKind.set(null);
        }

        @Override
        public void close() throws Exception {
            release();
            delegate.close();
        }
    }
}
