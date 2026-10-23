package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderPaymentCoordinator;
import com.loopers.application.order.OrderPaymentStockService;
import com.loopers.application.order.OrderService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration;
import com.loopers.infrastructure.payment.CircuitBreakingPaymentGateway;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real recovery controllers and PG sockets, using only the isolated test DB and synthetic payment material. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(20)
class PgPaymentRecoveryApiE2ETest {
    private static final String CARD = "0000-0000-0000-0000";
    private static final LocalTestPg PG = createPg();

    @Autowired private TestRestTemplate rest;
    @Autowired private OrderService orders;
    @Autowired private OrderPaymentCoordinator coordinator;
    @Autowired private OrderPaymentStockService stocks;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CircuitBreakingPaymentGateway gateway;

    @TestBean(methodName = "fingerprinter")
    private PaymentRequestFingerprinter fingerprinter;

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("commerce.test-pg.enabled", () -> true);
        registry.add("commerce.test-pg.base-url", () -> PG.baseUri().toString());
        registry.add("commerce.test-pg.callback-url", () -> PG.callbackUri().toString());
        registry.add("commerce.test-pg.lookup-response-timeout", () -> "250ms");
        registry.add("commerce.test-pg.lookup-total-timeout", () -> "750ms");
    }

    @BeforeEach
    void setUp() {
        // Observe one HTTP invocation: the default Apache test client can retry a callback POST after a 503.
        SimpleClientHttpRequestFactory singleRequest = new SimpleClientHttpRequestFactory();
        singleRequest.setConnectTimeout(Duration.ofSeconds(2));
        singleRequest.setReadTimeout(Duration.ofSeconds(10));
        rest.getRestTemplate().setRequestFactory(singleRequest);
        PG.reset();
        circuit().reset();
        ((RecoveryFingerprinter) fingerprinter).forbidUse = false;
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
    }

    @AfterEach
    void tearDown(CapturedOutput output) {
        PG.releaseResponses();
        cleanup.truncateAllTables();
        assertThat(output.getAll()).doesNotContain(CARD);
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void reconcileUsesOneExistingTransactionLookupAndAppliesTheFinalResultOnce(String terminal) {
        Fixture fixture = pending();
        PG.setStatus(transactionKey(fixture), terminal);

        JsonNode data = success(reconcile(fixture, "alice", null), 200);

        assertPayment(data, fixture, terminal);
        assertEffects(fixture, terminal);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.requests().getLast().rawPath()).isEqualTo("/api/v1/payments/" + transactionKey(fixture));
        assertThat(PG.requests().getLast().account()).isEqualTo("135135");
        var before = state();
        assertThat(success(reconcile(fixture, "alice", "{}"), 200)).isEqualTo(data);
        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @Test
    void reconcilePendingIsOneLookupNotTheInitialConfirmationWaitingLoop() {
        Fixture fixture = pending();
        var before = state();

        assertPayment(success(reconcile(fixture, "alice", "{}"), 202), fixture, "PENDING");

        assertThat(state()).isEqualTo(before);
        assertEffects(fixture, "PENDING");
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void callbackWithoutCustomerHeaderUsesAuthoritativeStatusRatherThanTheSuccessHint() {
        Fixture fixture = pending();

        acknowledged(callback(fixture, transactionKey(fixture), "SUCCESS"));

        assertEffects(fixture, "PENDING");
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void callbackAcknowledgesVerifiedTerminalAndDuplicateWithoutRepeatingEffects(String terminal) {
        Fixture fixture = pending();
        PG.setStatus(transactionKey(fixture), terminal);

        acknowledged(callback(fixture, transactionKey(fixture), "PENDING"));

        assertEffects(fixture, terminal);
        var before = state();
        acknowledged(callback(fixture, transactionKey(fixture), "PENDING"));
        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @Test
    void ownerIsCheckedBeforeReconcileBodyAndPointHistoryIsPreserved() {
        Fixture fixture = draft();
        var beforeDraft = state();

        failure(reconcile(fixture, "bob", "{\"cardNo\":\"ignored\"}"), 404, "ORDER_NOT_FOUND");
        failure(reconcile(fixture, "alice", null), 409, "PAYMENT_NOT_STARTED");
        assertThat(state()).isEqualTo(beforeDraft);

        orders.confirm("alice", fixture.orderId());
        var beforePoint = state();
        failure(reconcile(fixture, "alice", null), 409, "ORDER_ALREADY_CONFIRMED");
        failure(reconcile(fixture, "bob", null), 404, "ORDER_NOT_FOUND");
        assertThat(state()).isEqualTo(beforePoint);
        assertThat(PG.createCalls()).isZero();
        assertThat(PG.lookupCalls()).isZero();
    }

    @Test
    void unknownCallbackAttemptAndDifferentStoredTransactionDoNotContactPg() {
        Fixture fixture = pending();
        var before = state();

        failure(post("/api/v1/payments/callback", null,
            callbackBody("LP-ORD-9999999999", "NONEXISTENT-TX", "SUCCESS")), 404, "PAYMENT_ATTEMPT_NOT_FOUND");
        failure(callback(fixture, "DIFFERENT-TX", "SUCCESS"), 409, "PAYMENT_RESULT_CONFLICT");

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void lostCreateResponseIsRecoveredByOneOrderLookupWithoutCreatingAnotherTransaction() {
        Fixture fixture = draft();
        PG.nextCreateStatus("SUCCESS");
        PG.nextCreateMode(LocalTestPg.CreateMode.LOSE_RESPONSE_AFTER_CREATE);
        coordinator.startNew("alice", fixture.orderId(), fixture.key(), providerId(fixture), "TEST", CARD);
        assertEffects(fixture, "UNKNOWN");
        assertThat(transactionKey(fixture)).isNull();

        assertPayment(success(reconcile(fixture, "alice", null), 200), fixture, "SUCCESS");

        assertEffects(fixture, "SUCCESS");
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.requests().getLast().rawPath()).isEqualTo("/api/v1/payments");
        assertThat(PG.requests().getLast().rawQuery()).isEqualTo("orderId=" + providerId(fixture));
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @Test
    void noExternalTransactionIsUnknownRatherThanFailureOrPermissionToResend() {
        Fixture fixture = draft();
        stocks.prepareNew("alice", fixture.orderId(), fixture.key(), "135135", providerId(fixture), "TEST", CARD);

        assertPayment(success(reconcile(fixture, "alice", null), 202), fixture, "UNKNOWN");

        assertEffects(fixture, "UNKNOWN");
        assertThat(transactionKey(fixture)).isNull();
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isZero();
        assertThat(PG.transactionCount()).isZero();
    }

    @Test
    void fakeSuccessCallbackWithoutAnExternalTransactionCannotConfirmOrReleaseTheOrder() {
        Fixture fixture = draft();
        stocks.prepareNew("alice", fixture.orderId(), fixture.key(), "135135", providerId(fixture), "TEST", CARD);

        failure(callback(fixture, "FAKE-NONEXISTENT-TX", "SUCCESS"), 503, "PAYMENT_RESULT_UNAVAILABLE");

        assertEffects(fixture, "UNKNOWN");
        assertThat(transactionKey(fixture)).isNull();
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isZero();
        assertThat(PG.transactionCount()).isZero();
    }

    @Test
    void callbackCanRecoverLostCreateResponseUsingItsVerifiedTransactionKey() {
        Fixture fixture = draft();
        PG.nextCreateStatus("SUCCESS");
        PG.nextCreateMode(LocalTestPg.CreateMode.LOSE_RESPONSE_AFTER_CREATE);
        coordinator.startNew("alice", fixture.orderId(), fixture.key(), providerId(fixture), "TEST", CARD);
        assertThat(transactionKey(fixture)).isNull();

        // The reset local fixture deterministically assigned this key before dropping the create response.
        acknowledged(callback(fixture, "TEST-TX-1", "PENDING"));

        assertEffects(fixture, "SUCCESS");
        assertThat(transactionKey(fixture)).isEqualTo("TEST-TX-1");
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @Test
    void multipleOrderLookupTransactionsAreConflictRatherThanChoosingAnArbitrarySuccess() {
        Fixture fixture = draft();
        stocks.prepareNew("alice", fixture.orderId(), fixture.key(), "135135", providerId(fixture), "TEST", CARD);
        var before = state();
        PG.replyNext(200, "[" + transactionJson(providerId(fixture), "FIRST-TX", 200, "SUCCESS") + ","
            + transactionJson(providerId(fixture), "SECOND-TX", 200, "FAILED") + "]");

        failure(reconcile(fixture, "alice", null), 409, "PAYMENT_RESULT_CONFLICT");

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reconcile", "callback"})
    void recoveryDoesNotNeedCardMaterialOrAFingerprintForLegacyAttempts(String entry) {
        Fixture fixture = pending();
        PG.setStatus(transactionKey(fixture), "SUCCESS");
        jdbc.update("UPDATE payment_attempt SET request_fingerprint=NULL, fingerprint_version=NULL, key_version=NULL WHERE order_id=?", fixture.orderId());
        ((RecoveryFingerprinter) fingerprinter).forbidUse = true;

        if (entry.equals("reconcile")) {
            assertPayment(success(reconcile(fixture, "alice", null), 200), fixture, "SUCCESS");
        } else {
            acknowledged(callback(fixture, transactionKey(fixture), "FAILED"));
        }

        assertEffects(fixture, "SUCCESS");
        assertThat(jdbc.queryForObject("SELECT request_fingerprint FROM payment_attempt WHERE order_id=?", byte[].class, fixture.orderId())).isNull();
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reconcile", "callback"})
    void unsupportedStoredFingerprintVersionDoesNotPreventResultRecovery(String entry) {
        Fixture fixture = pending();
        PG.setStatus(transactionKey(fixture), "FAILED");
        jdbc.update("UPDATE payment_attempt SET key_version=999, fingerprint_version=999 WHERE order_id=?", fixture.orderId());
        ((RecoveryFingerprinter) fingerprinter).forbidUse = true;

        if (entry.equals("reconcile")) {
            assertPayment(success(reconcile(fixture, "alice", null), 200), fixture, "FAILED");
        } else {
            acknowledged(callback(fixture, transactionKey(fixture), "SUCCESS"));
        }

        assertEffects(fixture, "FAILED");
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"notFound", "serverError", "invalidJson", "strict422", "timeout"})
    void reconcileUnavailableObservationIs202UnknownAndKeepsReservation(String failure) {
        Fixture fixture = pending();
        unavailableReply(failure, fixture);

        assertPayment(success(reconcile(fixture, "alice", null), 202), fixture, "UNKNOWN");

        assertEffects(fixture, "UNKNOWN");
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"notFound", "serverError", "invalidJson", "strict422", "timeout"})
    void callbackWithoutVerifiedObservationReturns503AndDoesNotAcceptTheSuccessHint(String failure) {
        Fixture fixture = pending();
        unavailableReply(failure, fixture);

        failure(callback(fixture, transactionKey(fixture), "SUCCESS"), 503, "PAYMENT_RESULT_UNAVAILABLE");

        assertEffects(fixture, "UNKNOWN");
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reconcile", "callback"})
    void openLookupCircuitKeepsPreviousObservationAndDoesNotSendAnyHttp(String entry) {
        Fixture fixture = pending();
        circuit().transitionToOpenState();
        var before = state();

        if (entry.equals("reconcile")) {
            assertPayment(success(reconcile(fixture, "alice", null), 202), fixture, "PENDING");
        } else {
            failure(callback(fixture, transactionKey(fixture), "SUCCESS"), 503, "PAYMENT_RESULT_UNAVAILABLE");
        }

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "transaction", "amount"})
    void callbackRejectsAuthoritativeIdentityAndAmountConflictsWithoutMutatingFacts(String difference) {
        Fixture fixture = pending();
        var before = state();
        PG.replyNext(200, transactionJson(difference.equals("order") ? "LP-ORD-9999999999" : providerId(fixture),
            difference.equals("transaction") ? "ANOTHER-TX" : transactionKey(fixture),
            difference.equals("amount") ? 201 : 200, "SUCCESS"));

        failure(callback(fixture, transactionKey(fixture), "SUCCESS"), 409, "PAYMENT_RESULT_CONFLICT");

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void callbackQueriesEvenTerminalAttemptsAndRejectsAnOppositeAuthoritativeTerminal(String saved) {
        Fixture fixture = pending();
        PG.setStatus(transactionKey(fixture), saved);
        acknowledged(callback(fixture, transactionKey(fixture), saved));
        var before = state();
        PG.setStatus(transactionKey(fixture), saved.equals("SUCCESS") ? "FAILED" : "SUCCESS");

        failure(callback(fixture, transactionKey(fixture), saved), 409, "PAYMENT_RESULT_CONFLICT");

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void laterUnavailableOrPendingCallbackNeverRegressesSavedTerminal(String saved) {
        Fixture fixture = pending();
        PG.setStatus(transactionKey(fixture), saved);
        acknowledged(callback(fixture, transactionKey(fixture), saved));
        var before = state();
        PG.replyNext(404, "{}");
        failure(callback(fixture, transactionKey(fixture), "SUCCESS"), 503, "PAYMENT_RESULT_UNAVAILABLE");
        assertThat(state()).isEqualTo(before);
        PG.setStatus(transactionKey(fixture), "PENDING");

        acknowledged(callback(fixture, transactionKey(fixture), "PENDING"));

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "1", "{\"cardNo\":\"synthetic\"}", "{\"paymentAttemptKey\":\"unused\"}"})
    void reconcileRejectsUnexpectedBodyWithoutPgOrStateChanges(String body) {
        Fixture fixture = pending();
        var before = state();

        failure(reconcile(fixture, "alice", body), 400, "INVALID_REQUEST");

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void reconcileRequiresCustomerIdentityAndPreservesOwnerBoundaryForPendingOrders() {
        Fixture fixture = pending();
        var before = state();

        failure(reconcile(fixture, null, null), 400, "INVALID_REQUEST");
        failure(reconcile(fixture, "bob", "{}"), 404, "ORDER_NOT_FOUND");
        failure(post("/api/v1/orders/999999999/payment/reconcile", "alice", null), 404, "ORDER_NOT_FOUND");
        failure(post("/api/v1/orders/abc/payment/reconcile", "alice", null), 400, "INVALID_REQUEST");

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 255})
    void callbackIdentifierLimitsUseCodePointsRatherThanUtf16Length(int providerLength) {
        var before = state();

        failure(post("/api/v1/payments/callback", null, callbackBody("😀".repeat(providerLength),
            "😀".repeat(255), "SUCCESS")), 404, "PAYMENT_ATTEMPT_NOT_FOUND");

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(PG.createCalls()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidCallbacks")
    void malformedCallbackIsRejectedWithoutPgOrStateChanges(String body) {
        Fixture fixture = pending();
        var before = state();

        failure(post("/api/v1/payments/callback", null, body), 400, "INVALID_REQUEST");

        assertThat(state()).isEqualTo(before);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    private static Stream<String> invalidCallbacks() {
        String valid = "{\"providerOrderId\":\"LP-ORD-0000000001\",\"transactionKey\":\"TEST-TX-1\",\"status\":\"SUCCESS\"}";
        return Stream.of(null, "null", "[]", "{}", "{", valid.replace("\"SUCCESS\"", "1"),
            valid.replace("\"SUCCESS\"", "\"UNKNOWN\""), valid.replace("\"SUCCESS\"", "\"success\""),
            valid.replace("LP-ORD-0000000001", "short"), valid.replace("LP-ORD-0000000001", "😀".repeat(3)),
            valid.replace("LP-ORD-0000000001", " ".repeat(6)),
            valid.replace("LP-ORD-0000000001", "x".repeat(256)), valid.replace("TEST-TX-1", ""),
            valid.replace("TEST-TX-1", " "), valid.replace("TEST-TX-1", "x".repeat(256)),
            valid.replace("\"TEST-TX-1\"", "null"), valid.replace("\"TEST-TX-1\"", "123"),
            valid.substring(0, valid.length() - 1) + ",\"amount\":200}",
            valid.substring(0, valid.length() - 1) + ",\"providerAccount\":\"attacker\"}");
    }

    private void unavailableReply(String failure, Fixture fixture) {
        switch (failure) {
            case "notFound" -> PG.replyNext(404, "{}");
            case "serverError" -> PG.replyNext(500, "{}");
            case "invalidJson" -> PG.replyNext(200, "{invalid");
            case "strict422" -> PG.replyNext(422, "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\""
                + providerId(fixture) + "\",\"amount\":200,\"transactionCreated\":false}");
            case "timeout" -> PG.replyNextHeld(200, transactionJson(providerId(fixture), transactionKey(fixture), 200, "SUCCESS"));
            default -> throw new IllegalArgumentException("Unknown test failure mode");
        }
    }

    private String transactionJson(String order, String transaction, long amount, String status) {
        return "{\"orderId\":\"" + order + "\",\"transactionKey\":\"" + transaction + "\",\"amount\":" + amount
            + ",\"status\":\"" + status + "\"}";
    }

    private CircuitBreaker circuit() {
        return (CircuitBreaker) ReflectionTestUtils.getField(gateway, "circuit");
    }

    static PaymentRequestFingerprinter fingerprinter() {
        return new RecoveryFingerprinter();
    }

    private static final class RecoveryFingerprinter implements PaymentRequestFingerprinter {
        private final PaymentRequestFingerprinter delegate = PaymentFingerprintTestConfiguration.create();
        private boolean forbidUse;

        @Override
        public PaymentRequestFingerprint calculate(PaymentFingerprintInput input) {
            assertThat(forbidUse).as("recovery must not calculate a request fingerprint").isFalse();
            return delegate.calculate(input);
        }

        @Override
        public boolean matches(PaymentFingerprintInput input, PaymentRequestFingerprint stored) {
            assertThat(forbidUse).as("recovery must not require request comparison keys").isFalse();
            return delegate.matches(input, stored);
        }
    }

    private Fixture draft() {
        Product product = products.save(new Product(brands.save(new Brand("복구 브랜드")), "복구 상품", 100, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(orderId, product.getId(), UUID.randomUUID());
    }

    private Fixture pending() {
        Fixture fixture = draft();
        coordinator.startNew("alice", fixture.orderId(), fixture.key(), providerId(fixture), "TEST", CARD);
        return fixture;
    }

    private void assertPayment(JsonNode data, Fixture fixture, String payment) {
        assertThat(data.path("orderId").asLong()).isEqualTo(fixture.orderId());
        assertThat(data.path("paymentMethod").asText()).isEqualTo("PG");
        assertThat(data.path("payment").path("paymentAttemptKey").asText()).isEqualTo(fixture.key().toString());
        assertThat(data.path("payment").path("status").asText()).isEqualTo(payment);
        assertThat(data.path("links").path("order").asText()).isEqualTo("/api/v1/orders/" + fixture.orderId());
        assertThat(data.path("links").path("reconcile").asText()).isEqualTo(reconcilePath(fixture));
        assertThat(data.toString()).doesNotContain("cardNo", "cardType", "fingerprint", "providerOrderId", "transactionKey");
    }

    private void assertEffects(Fixture fixture, String payment) {
        String order = payment.equals("SUCCESS") ? "CONFIRMED" : payment.equals("FAILED") ? "PAYMENT_FAILED" : "PAYMENT_PENDING";
        String reservation = payment.equals("SUCCESS") ? "CONSUMED" : payment.equals("FAILED") ? "RELEASED" : "HELD";
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo(order);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId())).isEqualTo(payment);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, fixture.orderId())).isEqualTo(reservation);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId())).isEqualTo(payment.equals("FAILED") ? 5 : 3);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?", Integer.class, fixture.orderId())).isEqualTo(1);
    }

    private Map<String, List<Map<String, Object>>> state() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "revision", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT id,order_id,HEX(attempt_key) AS attempt_key,status,transaction_key,provider_order_id,"
                + "HEX(request_fingerprint) AS digest,fingerprint_version,key_version,send_started_at FROM payment_attempt ORDER BY id"));
    }

    private String transactionKey(Fixture fixture) {
        return jdbc.queryForObject("SELECT transaction_key FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId());
    }

    private String providerId(Fixture fixture) {
        return "LP-ORD-%010d".formatted(fixture.orderId());
    }

    private ResponseEntity<JsonNode> reconcile(Fixture fixture, String requester, String body) {
        return post(reconcilePath(fixture), requester, body);
    }

    private String reconcilePath(Fixture fixture) {
        return "/api/v1/orders/" + fixture.orderId() + "/payment/reconcile";
    }

    private ResponseEntity<JsonNode> callback(Fixture fixture, String transaction, String status) {
        return post("/api/v1/payments/callback", null, callbackBody(providerId(fixture), transaction, status));
    }

    private String callbackBody(String providerOrderId, String transaction, String status) {
        return "{\"providerOrderId\":\"" + providerOrderId + "\",\"transactionKey\":\"" + transaction + "\",\"status\":\"" + status + "\"}";
    }

    private ResponseEntity<JsonNode> post(String path, String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void acknowledged(ResponseEntity<JsonNode> response) {
        JsonNode data = success(response, 200);
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.path("acknowledged").isBoolean()).isTrue();
        assertThat(data.path("acknowledged").booleanValue()).isTrue();
    }

    private JsonNode success(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private void failure(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private static LocalTestPg createPg() {
        try {
            return new LocalTestPg();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record Fixture(long orderId, long productId, UUID key) { }
}
