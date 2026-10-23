package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderPaymentCoordinator;
import com.loopers.application.order.OrderPaymentStockService;
import com.loopers.application.order.OrderService;
import com.loopers.application.order.PaymentConfirmationTiming;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real customer HTTP, isolated test DB, and real local PG HTTP; only synthetic card/key values are used. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(20)
class PgOrderConfirmApiE2ETest {
    private static final String CARD = "0000-0000-0000-0000";
    private static final String OTHER_CARD = "1111-1111-1111-1111";
    private static final LocalTestPg PG = createPg();

    @Autowired private TestRestTemplate rest;
    @Autowired private MockMvc mvc;
    @Autowired private OrderService orders;
    @Autowired private OrderPaymentCoordinator coordinator;
    @Autowired private OrderPaymentStockService stocks;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @TestBean
    private PaymentConfirmationTiming paymentConfirmationTiming;

    private int createsBefore;
    private int lookupsBefore;
    private int transactionsBefore;

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("commerce.test-pg.enabled", () -> true);
        registry.add("commerce.test-pg.base-url", () -> PG.baseUri().toString());
        registry.add("commerce.test-pg.callback-url", () -> PG.callbackUri().toString());
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        PG.nextCreateMode(LocalTestPg.CreateMode.NORMAL);
        PG.nextCreateStatus("SUCCESS");
        createsBefore = PG.createCalls();
        lookupsBefore = PG.lookupCalls();
        transactionsBefore = PG.transactionCount();
        ((FastTiming) paymentConfirmationTiming).reset();
    }

    @AfterEach
    void tearDown(CapturedOutput output) {
        PG.releaseResponses();
        cleanup.truncateAllTables();
        assertThat(output.getAll().contains(CARD)).as("synthetic card is absent from application logs").isFalse();
        assertThat(output.getAll().contains(OTHER_CARD)).as("changed synthetic card is absent from logs").isFalse();
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,CONFIRMED,CONSUMED,3", "FAILED,PAYMENT_FAILED,RELEASED,5"})
    void firstConfirmSeparatesApiSuccessFromPgResultAndNeverChargesPoints(String payment, String order,
                                                                        String reservation, int stock) {
        Fixture fixture = draft();
        PG.nextCreateStatus(payment);

        JsonNode data = success(confirm(fixture, fixture.key(), CARD), 200);

        assertPayment(data, fixture, order, payment);
        assertThat(data.path("totalAmount").asLong()).isEqualTo(200);
        assertThat(data.path("items").get(0).path("productName").asText()).isEqualTo("상품");
        assertThat(data.has("paidAmount")).isEqualTo(payment.equals("SUCCESS"));
        assertThat(data.has("confirmedAt")).isEqualTo(payment.equals("SUCCESS"));
        if (payment.equals("SUCCESS")) {
            assertThat(data.path("paidAmount").asLong()).isEqualTo(200);
        }
        assertState(fixture, order, payment, reservation, stock);
        assertCalls(1, 0, 1);
        assertThat(PG.requests().getLast().orderId()).isEqualTo(providerId(fixture.orderId()));
        assertThat(PG.requests().getLast().amount()).isEqualTo(200);
        assertThat(PG.requests().getLast().cardMatchesExpected()).isTrue();
        assertThat(jdbc.queryForObject("SELECT OCTET_LENGTH(request_fingerprint) FROM payment_attempt WHERE order_id=?",
            Integer.class, fixture.orderId())).isEqualTo(32);
        assertThat(success(get(fixture.orderId(), "alice"), 200)).isEqualTo(data);
        assertCalls(1, 0, 1);
    }

    @Test
    void strictNoEffectRejectionReturnsFailedAndReturnsReservationExactlyOnce() {
        Fixture fixture = draft();
        PG.nextCreateMode(LocalTestPg.CreateMode.REJECT_BEFORE_CREATE);

        JsonNode data = success(confirm(fixture, fixture.key(), CARD), 200);

        assertPayment(data, fixture, "PAYMENT_FAILED", "FAILED");
        assertState(fixture, "PAYMENT_FAILED", "FAILED", "RELEASED", 5);
        assertThat(jdbc.queryForObject("SELECT transaction_key FROM payment_attempt WHERE order_id=?",
            String.class, fixture.orderId())).isNull();
        var before = state();
        assertThat(success(confirm(fixture, fixture.key(), CARD), 200)).isEqualTo(data);
        assertThat(state()).isEqualTo(before);
        assertCalls(1, 0, 0);
    }

    @Test
    void pendingFirstConfirmUsesTwelveFiniteLookupsAndReturns202WithoutReturningReservation() {
        Fixture fixture = draft();
        PG.nextCreateStatus("PENDING");

        JsonNode data = success(confirm(fixture, fixture.key(), CARD), 202);

        assertPayment(data, fixture, "PAYMENT_PENDING", "PENDING");
        assertState(fixture, "PAYMENT_PENDING", "PENDING", "HELD", 3);
        assertCalls(1, 12, 1);
        assertThat(paymentConfirmationTiming.nanoTime()).isEqualTo(Duration.ofSeconds(150).toNanos());
        assertThat(PG.requests().subList(PG.requests().size() - 12, PG.requests().size()))
            .allSatisfy(request -> assertThat(request.rawPath()).startsWith("/api/v1/payments/TEST-TX-"));
        var before = state();
        assertThat(success(confirm(fixture, fixture.key(), CARD), 202)).isEqualTo(data);
        assertThat(state()).isEqualTo(before);
        assertCalls(1, 12, 1);
        assertThat(paymentConfirmationTiming.nanoTime()).isEqualTo(Duration.ofSeconds(150).toNanos());
    }

    @Test
    void lostCreateResponseUsesOrderLookupThenTransactionLookupWithoutAnotherCreate() {
        Fixture fixture = draft();
        PG.nextCreateStatus("PENDING");
        PG.nextCreateMode(LocalTestPg.CreateMode.LOSE_RESPONSE_AFTER_CREATE);
        int firstRequest = PG.requests().size();

        JsonNode data = success(confirm(fixture, fixture.key(), CARD), 202);

        assertPayment(data, fixture, "PAYMENT_PENDING", "PENDING");
        assertState(fixture, "PAYMENT_PENDING", "PENDING", "HELD", 3);
        assertCalls(1, 12, 1);
        assertThat(PG.requests().get(firstRequest + 1).rawPath()).isEqualTo("/api/v1/payments");
        assertThat(PG.requests().get(firstRequest + 1).rawQuery()).isEqualTo("orderId=" + providerId(fixture.orderId()));
        assertThat(PG.requests().get(firstRequest + 2).rawPath()).startsWith("/api/v1/payments/TEST-TX-");
    }

    @Test
    void unresolvedCreateAndEmptyLookupsEndAsUnknownWithReservationStillHeld() {
        Fixture fixture = draft();
        PG.replyNext(500, "{}");

        JsonNode data = success(confirm(fixture, fixture.key(), CARD), 202);

        assertPayment(data, fixture, "PAYMENT_PENDING", "UNKNOWN");
        assertState(fixture, "PAYMENT_PENDING", "UNKNOWN", "HELD", 3);
        assertCalls(1, 12, 0);
        assertThat(jdbc.queryForObject("SELECT transaction_key FROM payment_attempt WHERE order_id=?",
            String.class, fixture.orderId())).isNull();
        assertThat(paymentConfirmationTiming.nanoTime()).isEqualTo(Duration.ofSeconds(150).toNanos());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void firstRequestStopsEarlyWhenALaterLookupConfirmsFinalResult(String terminal) {
        Fixture fixture = draft();
        PG.nextCreateStatus("PENDING");
        ((FastTiming) paymentConfirmationTiming).afterSleep = () -> {
            String transaction = jdbc.queryForObject("SELECT transaction_key FROM payment_attempt WHERE order_id=?",
                String.class, fixture.orderId());
            PG.setStatus(transaction, terminal);
        };

        JsonNode data = success(confirm(fixture, fixture.key(), CARD), 200);

        assertPayment(data, fixture, terminal.equals("SUCCESS") ? "CONFIRMED" : "PAYMENT_FAILED", terminal);
        assertCalls(1, 2, 1);
        assertThat(paymentConfirmationTiming.nanoTime()).isEqualTo(Duration.ofSeconds(5).toNanos());
        assertState(fixture, terminal.equals("SUCCESS") ? "CONFIRMED" : "PAYMENT_FAILED", terminal,
            terminal.equals("SUCCESS") ? "CONSUMED" : "RELEASED", terminal.equals("SUCCESS") ? 3 : 5);
    }

    @Test
    void mismatchedExternalResultReturnsConflictWithoutInventingFailureOrReturningStock() {
        Fixture fixture = draft();
        PG.replyNext(202, "{\"transactionKey\":\"TEST-CONFLICT\",\"orderId\":\"" + providerId(fixture.orderId())
            + "\",\"amount\":999,\"status\":\"SUCCESS\"}");

        failure(confirm(fixture, fixture.key(), CARD), 409, "PAYMENT_RESULT_CONFLICT");

        assertState(fixture, "PAYMENT_PENDING", "NOT_OBSERVED", "HELD", 3);
        assertCalls(1, 0, 0);
        assertPayment(success(get(fixture.orderId(), "alice"), 200), fixture, "PAYMENT_PENDING", "NOT_OBSERVED");
    }

    @Test
    void sameRequestUsesPersistedProviderIdInsteadOfRegeneratingTheCurrentFormat() {
        Fixture fixture = draft();
        stocks.prepareNew("alice", fixture.orderId(), fixture.key(), "135135", "LEGACY-PROVIDER-42", "TEST", CARD);
        var before = state();

        JsonNode data = success(confirm(fixture, fixture.key(), CARD), 202);

        assertPayment(data, fixture, "PAYMENT_PENDING", "NOT_OBSERVED");
        assertThat(state()).isEqualTo(before);
        assertCalls(0, 0, 0);
    }

    @Test
    void zeroPointBalanceDoesNotPreventPgPayment() {
        Fixture fixture = draft();
        jdbc.update("UPDATE user SET point_balance=0 WHERE id=1");

        assertPayment(success(confirm(fixture, fixture.key(), CARD), 200), fixture, "CONFIRMED", "SUCCESS");

        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isZero();
        assertCalls(1, 0, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stock", "deleted-product", "deleted-brand"})
    void existingStockAndDeletionGuardsStillRunBeforeAnyPgDispatch(String scenario) {
        Fixture fixture = draft();
        if (scenario.equals("stock")) {
            jdbc.update("UPDATE product SET stock_quantity=1 WHERE id=?", fixture.productId());
        } else if (scenario.equals("deleted-product")) {
            jdbc.update("UPDATE product SET deleted_at=NOW() WHERE id=?", fixture.productId());
        } else {
            jdbc.update("UPDATE brand SET deleted_at=NOW() WHERE id=(SELECT brand_id FROM product WHERE id=?)", fixture.productId());
        }
        var before = state();

        failure(confirm(fixture, fixture.key(), CARD), scenario.equals("stock") ? 409 : 404,
            scenario.equals("stock") ? "INSUFFICIENT_STOCK" : "PRODUCT_NOT_FOUND");

        assertThat(state()).isEqualTo(before);
        assertCalls(0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "total", "subtotal"})
    void corruptStoredFactsRemainServerErrorsWithoutStartingPayment(String corruption) {
        Fixture fixture = draft();
        switch (corruption) {
            case "quantity" -> jdbc.update("UPDATE order_item SET quantity=0 WHERE order_id=?", fixture.orderId());
            case "total" -> jdbc.update("UPDATE `order` SET total_amount=-1 WHERE id=?", fixture.orderId());
            case "subtotal" -> jdbc.update("UPDATE order_item SET unit_price_snapshot=? WHERE order_id=?", Long.MAX_VALUE, fixture.orderId());
            default -> throw new IllegalArgumentException("Unknown test fixture");
        }
        var before = state();

        failure(confirm(fixture, fixture.key(), CARD), 500, "INTERNAL_ERROR");

        assertThat(state()).isEqualTo(before);
        assertCalls(0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentConfirmCannotDispatchOrWaitAgainWhileTheWinnerIsWaitingForPg(boolean changedCard) throws Exception {
        Fixture fixture = draft();
        PG.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var first = worker.submit(() -> confirm(fixture, fixture.key(), CARD));
            assertThat(PG.awaitCreate(Duration.ofSeconds(5))).isTrue();

            var repeated = confirm(fixture, fixture.key(), changedCard ? OTHER_CARD : CARD);

            if (changedCard) {
                failure(repeated, 409, "PAYMENT_REQUEST_CONFLICT");
            } else {
                assertPayment(success(repeated, 202), fixture, "PAYMENT_PENDING", "NOT_OBSERVED");
            }
            assertThat(first.isDone()).isFalse();
            assertState(fixture, "PAYMENT_PENDING", "NOT_OBSERVED", "HELD", 3);
            assertCalls(1, 0, 1);
            assertThat(paymentConfirmationTiming.nanoTime()).isZero();
            PG.releaseResponses();
            assertPayment(success(first.get(5, TimeUnit.SECONDS), 200), fixture, "CONFIRMED", "SUCCESS");
            assertState(fixture, "CONFIRMED", "SUCCESS", "CONSUMED", 3);
            assertCalls(1, 0, 1);
        } finally {
            PG.releaseResponses();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"NOT_OBSERVED,202,PAYMENT_PENDING", "PENDING,202,PAYMENT_PENDING", "UNKNOWN,202,PAYMENT_PENDING",
        "SUCCESS,200,CONFIRMED", "FAILED,200,PAYMENT_FAILED"})
    void identicalRetryReturnsSavedStateWithoutStartingAnotherLongWaitOrPgRequest(String payment, int status, String order) {
        Fixture fixture = draft();
        prepareState(fixture, payment);
        var before = state();
        int createCount = PG.createCalls();
        int lookupCount = PG.lookupCalls();

        JsonNode data = success(confirm(fixture, fixture.key(), CARD), status);

        assertPayment(data, fixture, order, payment);
        assertThat(state()).isEqualTo(before);
        assertThat(PG.createCalls()).isEqualTo(createCount);
        assertThat(PG.lookupCalls()).isEqualTo(lookupCount);
        assertThat(success(get(fixture.orderId(), "alice"), 200)).isEqualTo(data);
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"card", "type", "key"})
    void differentRequestReturns409AndPreservesTheExistingAttempt(String difference) {
        Fixture fixture = draft();
        prepareState(fixture, "PENDING");
        var before = state();
        String body = paymentBody(difference.equals("key") ? UUID.randomUUID() : fixture.key(),
            difference.equals("type") ? "OTHER_TEST" : "TEST", difference.equals("card") ? OTHER_CARD : CARD);

        failure(request(HttpMethod.POST, confirmPath(fixture.orderId()), "alice", body), 409, "PAYMENT_REQUEST_CONFLICT");

        assertThat(state()).isEqualTo(before);
        assertCalls(1, 0, 1);
    }

    @Test
    void globallyReusedAttemptKeyCannotPayForAnotherOrder() {
        Fixture first = draft();
        Fixture second = draft();
        prepareState(first, "PENDING");
        var before = state();

        failure(confirm(second, first.key(), CARD), 409, "PAYMENT_REQUEST_CONFLICT");

        assertThat(state()).isEqualTo(before);
        assertCalls(1, 0, 1);
    }

    @ParameterizedTest
    @CsvSource({"legacy,409,PAYMENT_REQUEST_UNVERIFIABLE", "key,503,PAYMENT_VERIFICATION_UNAVAILABLE",
        "format,503,PAYMENT_VERIFICATION_UNAVAILABLE"})
    void unavailableComparisonKeepsExistingRecordAndStillAllowsReadOnlyQueries(String kind, int status, String code) {
        Fixture fixture = draft();
        prepareState(fixture, "PENDING");
        if (kind.equals("legacy")) {
            jdbc.update("UPDATE payment_attempt SET request_fingerprint=NULL, fingerprint_version=NULL, key_version=NULL WHERE order_id=?",
                fixture.orderId());
        } else {
            String column = kind.equals("key") ? "key_version" : "fingerprint_version";
            jdbc.update("UPDATE payment_attempt SET " + column + "=999 WHERE order_id=?", fixture.orderId());
        }
        var before = state();

        failure(confirm(fixture, fixture.key(), CARD), status, code);

        JsonNode detail = success(get(fixture.orderId(), "alice"), 200);
        assertPayment(detail, fixture, "PAYMENT_PENDING", "PENDING");
        assertThat(state()).isEqualTo(before);
        assertCalls(1, 0, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PENDING", "POINT"})
    void ownershipPrecedesMissingBodyAndDoesNotExposeOrderOrPaymentState(String kind) {
        Fixture fixture = draft();
        if (kind.equals("POINT")) {
            orders.confirm("alice", fixture.orderId());
        } else if (kind.equals("PENDING")) {
            prepareState(fixture, kind);
        }
        var before = state();

        for (long id : new long[]{fixture.orderId(), Long.MAX_VALUE}) {
            failure(request(HttpMethod.POST, confirmPath(id), "bob", null), 404, "ORDER_NOT_FOUND");
            failure(get(id, "bob"), 404, "ORDER_NOT_FOUND");
        }

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void historicalPointOrderRetainsStoredFactsAndBodylessReconfirmationError() {
        Fixture fixture = draft();
        orders.confirm("alice", fixture.orderId());
        jdbc.update("UPDATE product SET name='변경 상품', price=999, deleted_at=NOW() WHERE id=?", fixture.productId());
        var before = state();

        JsonNode detail = success(get(fixture.orderId(), "alice"), 200);
        failure(request(HttpMethod.POST, confirmPath(fixture.orderId()), "alice", null), 409, "ORDER_ALREADY_CONFIRMED");

        assertThat(detail.path("paymentMethod").asText()).isEqualTo("POINT");
        assertThat(detail.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(detail.path("paidAmount").asLong()).isEqualTo(200);
        assertThat(detail.path("items").get(0).path("productName").asText()).isEqualTo("상품");
        assertThat(detail.has("payment")).isFalse();
        assertThat(detail.has("links")).isFalse();
        assertThat(state()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(800);
        assertCalls(0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED", "SUCCESS"})
    void customerAndAdminQueriesExposeSameStoredPaymentWithoutPgEffects(String payment) {
        Fixture fixture = draft();
        prepareState(fixture, payment);
        var before = state();
        String order = payment.equals("SUCCESS") ? "CONFIRMED"
            : payment.equals("FAILED") ? "PAYMENT_FAILED" : "PAYMENT_PENDING";

        JsonNode customer = success(get(fixture.orderId(), "alice"), 200);
        JsonNode mine = success(request(HttpMethod.GET, "/api/v1/orders", "alice", null), 200);
        JsonNode admin = success(AdminMockMvc.exchange(mvc, HttpMethod.GET,
            "/api-admin/v1/orders/" + fixture.orderId(), "admin", null), 200);
        JsonNode filtered = success(AdminMockMvc.exchange(mvc, HttpMethod.GET,
            "/api-admin/v1/orders?userId=alice&status=" + order, "admin", null), 200);

        assertPayment(customer, fixture, order, payment);
        assertThat(mine.path("items").get(0)).isEqualTo(customer);
        assertThat(admin.path("userId").asText()).isEqualTo("alice");
        assertThat(admin.path("payment")).isEqualTo(customer.path("payment"));
        assertThat(admin.path("paymentMethod")).isEqualTo(customer.path("paymentMethod"));
        assertThat(filtered.path("totalElements").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").get(0)).isEqualTo(admin);
        assertThat(success(request(HttpMethod.GET, "/api/v1/orders", "bob", null), 200)
            .path("totalElements").asInt()).isZero();
        assertThat(state()).isEqualTo(before);
        assertCalls(1, 0, 1);
    }

    @Test
    void draftReadHasNoInventedPaymentAndNeverContactsPg() {
        Fixture fixture = draft();
        var before = state();

        JsonNode detail = success(get(fixture.orderId(), "alice"), 200);

        assertThat(detail.path("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.has("paymentMethod")).isFalse();
        assertThat(detail.has("payment")).isFalse();
        assertThat(detail.has("links")).isFalse();
        assertThat(state()).isEqualTo(before);
        assertCalls(0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"paymentAttemptKey\":\"not-a-uuid\",\"cardType\":\"TEST\",\"cardNo\":\"synthetic\"}",
        "{\"paymentAttemptKey\":\"12345678-abcd-4abc-8abc-123456789abc\",\"cardType\":\"TEST\",\"cardNo\":1234}",
        "{\"paymentAttemptKey\":\"12345678-abcd-4abc-8abc-123456789abc\",\"cardType\":\"TEST\",\"cardNo\":\"synthetic\",\"amount\":1}"})
    void invalidPgInputDoesNotReserveStockOrSendPayment(String body) {
        Fixture fixture = draft();
        var before = state();

        failure(request(HttpMethod.POST, confirmPath(fixture.orderId()), "alice", body), 400, "INVALID_REQUEST");

        assertThat(state()).isEqualTo(before);
        assertCalls(0, 0, 0);
    }

    private void prepareState(Fixture fixture, String payment) {
        if (payment.equals("NOT_OBSERVED")) {
            stocks.prepareNew("alice", fixture.orderId(), fixture.key(), "135135", providerId(fixture.orderId()), "TEST", CARD);
        } else {
            PG.nextCreateStatus(payment.equals("UNKNOWN") ? "PENDING" : payment);
            coordinator.startOrReuse("alice", fixture.orderId(), fixture.key(), providerId(fixture.orderId()), "TEST", CARD);
            if (payment.equals("UNKNOWN")) {
                stocks.markUnknown(fixture.orderId(), fixture.key());
            }
        }
    }

    private Fixture draft() {
        Product product = products.save(new Product(brands.save(new Brand("브랜드")), "상품", 100, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(orderId, product.getId(), UUID.randomUUID());
    }

    private void assertPayment(JsonNode data, Fixture fixture, String orderStatus, String paymentStatus) {
        assertThat(data.path("orderId").asLong()).isEqualTo(fixture.orderId());
        assertThat(data.path("status").asText()).isEqualTo(orderStatus);
        assertThat(data.path("paymentMethod").asText()).isEqualTo("PG");
        assertThat(data.path("payment").path("paymentAttemptKey").asText()).isEqualTo(fixture.key().toString());
        assertThat(data.path("payment").path("status").asText()).isEqualTo(paymentStatus);
        assertThat(data.path("links").path("order").asText()).isEqualTo("/api/v1/orders/" + fixture.orderId());
        assertThat(data.path("links").path("reconcile").asText()).isEqualTo("/api/v1/orders/" + fixture.orderId() + "/payment/reconcile");
        assertThat(data.toString()).doesNotContain("cardNo", "cardType", "fingerprint", "keyVersion", "providerOrderId",
            "providerAccount", "transactionKey", "sendStartedAt");
    }

    private void assertState(Fixture fixture, String order, String payment, String reservation, int stock) {
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo(order);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId())).isEqualTo(payment);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, fixture.orderId())).isEqualTo(reservation);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId())).isEqualTo(stock);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?", Integer.class, fixture.orderId())).isEqualTo(1);
    }

    private void assertCalls(int creates, int lookups, int transactions) {
        assertThat(PG.createCalls() - createsBefore).isEqualTo(creates);
        assertThat(PG.lookupCalls() - lookupsBefore).isEqualTo(lookups);
        assertThat(PG.transactionCount() - transactionsBefore).isEqualTo(transactions);
    }

    private Map<String, List<Map<String, Object>>> state() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT id,order_id,HEX(attempt_key) AS attempt_key,status,transaction_key,provider_order_id,"
                + "HEX(request_fingerprint) AS digest,fingerprint_version,key_version FROM payment_attempt ORDER BY id"));
    }

    private ResponseEntity<JsonNode> confirm(Fixture fixture, UUID key, String card) {
        return request(HttpMethod.POST, confirmPath(fixture.orderId()), "alice", paymentBody(key, "TEST", card));
    }

    private ResponseEntity<JsonNode> get(long orderId, String requester) {
        return request(HttpMethod.GET, "/api/v1/orders/" + orderId, requester, null);
    }

    private String paymentBody(UUID key, String type, String card) {
        return "{\"paymentAttemptKey\":\"" + key + "\",\"cardType\":\"" + type + "\",\"cardNo\":\"" + card + "\"}";
    }

    private String confirmPath(long orderId) {
        return "/api/v1/orders/" + orderId + "/confirm";
    }

    private static String providerId(long orderId) {
        return "LP-ORD-%010d".formatted(orderId);
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
        assertThat(response.getHeaders().containsKey("Retry-After")).isFalse();
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", requester);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static LocalTestPg createPg() {
        try {
            return new LocalTestPg();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static PaymentConfirmationTiming paymentConfirmationTiming() {
        return new FastTiming();
    }

    private static final class FastTiming implements PaymentConfirmationTiming {
        private long elapsedNanos;
        private Runnable afterSleep = () -> { };

        @Override
        public long nanoTime() {
            return elapsedNanos;
        }

        @Override
        public void sleep(Duration duration) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(duration.isNegative()).isFalse();
            elapsedNanos += duration.toNanos();
            afterSleep.run();
        }

        @Override
        public double jitterFactor() {
            return 1.0;
        }

        void reset() {
            elapsedNanos = 0;
            afterSleep = () -> { };
        }
    }

    private record Fixture(long orderId, long productId, UUID key) { }
}
