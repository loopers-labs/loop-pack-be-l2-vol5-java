package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PaymentConfirmCommand;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.PastPointOrderFixture;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.PgTestSupport;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.pgsimulator.PaymentStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class PgPaymentApiIntegrationTest extends PgTestSupport {

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private PaymentGateway gateway;
    @Autowired private TestRestTemplate http;
    @Autowired private UserFixture users;
    @Autowired private ProductFixture products;
    @Autowired private OrderFacade orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private Environment environment;
    @Autowired private PastPointOrderFixture pastPoints;
    @LocalServerPort private int port;

    private Long userId;
    private Long orderId;
    private ProductModel product;

    @BeforeEach
    void prepare() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        http.getRestTemplate().setRequestFactory(factory);
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(gateway).create(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(gateway).findByTransactionKey(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(gateway).findByOrderId(org.mockito.ArgumentMatchers.anyString());
        userId = users.createUserWithoutPoint().getId();
        product = products.createProduct("상품", 2000L, 5L);
        orderId = orders.create(userId, List.of(new OrderItemCommand(product.getId(), 2L))).id();
        assertThat(environment.getProperty("commerce.pg.callback-url"))
            .isEqualTo("http://127.0.0.1:" + port + "/api/v1/payments/callback");
    }

    @AfterEach
    void clean() {
        awaitPgIdle();
        cleanup.truncateAllTables();
    }

    private ResponseEntity<JsonNode> request(String path, HttpMethod method, Object body, Long requester) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (requester != null) {
            headers.set("X-USER-ID", requester.toString());
        }
        return http.exchange("http://127.0.0.1:" + port + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String orderPath() { return "/api/v1/orders/" + orderId; }

    private ResponseEntity<JsonNode> confirm() {
        return request(orderPath() + "/confirm", HttpMethod.POST, PgCommands.forOrder(orderId), userId);
    }

    private ResponseEntity<JsonNode> reconcile() {
        return request(orderPath() + "/payment/reconcile", HttpMethod.POST, null, userId);
    }

    private String key() {
        return jdbc.queryForObject("SELECT transaction_key FROM payment WHERE order_id = ?", String.class, orderId);
    }

    private long stock() {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Long.class, product.getId());
    }

    private long histories(String cause) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = ?",
            Long.class, orderId, cause);
    }

    private String savedStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    @Test
    void pendingWithoutPointHasCorrectContractAndReadDoesNotQueryPg() {
        ResponseEntity<JsonNode> response = confirm();
        JsonNode data = response.getBody().path("data");
        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(202),
            () -> assertThat(data.path("status").asText()).isEqualTo("PAYMENT_PENDING"),
            () -> assertThat(data.path("totalAmount").asLong()).isEqualTo(4000),
            () -> assertThat(data.path("pointAmount").asLong()).isZero(),
            () -> assertThat(data.has("paidAmount")).isFalse(),
            () -> assertThat(data.path("payment").path("amount").asLong()).isEqualTo(4000),
            () -> assertThat(data.path("payment").path("status").asText()).isEqualTo("PENDING"),
            () -> assertThat(data.path("payment").path("verificationStatus").asText()).isEqualTo("VERIFIED"),
            () -> assertThat(data.path("payment").path("orderUrl").asText()).isEqualTo(orderPath()),
            () -> assertThat(data.path("payment").path("reconcileUrl").asText())
                .isEqualTo(orderPath() + "/payment/reconcile"),
            () -> assertThat(stock()).isEqualTo(3),
            () -> assertThat(histories("ORDER_RESERVATION")).isEqualTo(1),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_history", Long.class)).isZero(),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point", Long.class)).isZero()
        );
        assertThat(confirm().getStatusCode().value()).isEqualTo(202);
        assertThat(request(orderPath(), HttpMethod.GET, null, userId).getStatusCode().value()).isEqualTo(200);
        JsonNode list = request("/api/v1/orders", HttpMethod.GET, null, userId).getBody().path("data");
        assertThat(list.path("items").get(0).path("payment")).isEqualTo(data.path("payment"));
        assertThat(data.toString()).doesNotContain("transactionKey", "providerOrderId", "cardNo", "orderTotal", "usedPointAmount");
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        assertThat(pg.state().counts().lookupByKeyReceived()).isZero();
        assertThat(pg.state().counts().lookupByOrderReceived()).isZero();
    }

    @Test
    void immediateSuccessUsesStoredPriceAndDoesNotConsumePoints() {
        success(orderId);
        // 상품 가격은 주문의 고정 단가를 바꾸지 않는다.
        jdbc.update("UPDATE product SET price = 9000 WHERE id = ?", product.getId());
        ResponseEntity<JsonNode> response = confirm();
        JsonNode data = response.getBody().path("data");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(data.path("paidAmount").asLong()).isEqualTo(4000);
        assertThat(data.path("payment").path("status").asText()).isEqualTo("SUCCESS");
        assertThat(savedStatus()).isEqualTo("CONFIRMED");
        assertThat(confirm().getStatusCode().value()).isEqualTo(200);
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        assertThat(histories("ORDER_RESERVATION")).isEqualTo(1);
        assertThat(histories("ORDER_RELEASE")).isZero();
    }

    @Test
    void exactRejectionReleasesOnceAndNeverCreatesTransaction() {
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(orderId),
            ResponsePlan.outcome(CreateOutcome.REJECT_BEFORE_CREATE));
        ResponseEntity<JsonNode> response = confirm();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("data").path("payment").path("status").asText()).isEqualTo("FAILED");
        assertThat(stock()).isEqualTo(5);
        assertThat(histories("ORDER_RELEASE")).isEqualTo(1);
        assertThat(pg.state().transactions()).isEmpty();
        assertThat(confirm().getStatusCode().value()).isEqualTo(200);
        assertThat(histories("ORDER_RELEASE")).isEqualTo(1);
    }

    @Test
    void createResponseLossKeepsReservationAndReconcileRecoversExistingTransaction() {
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(orderId), ResponsePlan.closeEarly());
        assertThat(confirm().getStatusCode().value()).isEqualTo(202);
        assertThat(key()).isNull();
        String transaction = pg.state().transactions().getFirst().transactionKey();
        pg.setStatus(transaction, PaymentStatus.SUCCESS);
        assertThat(reconcile().getStatusCode().value()).isEqualTo(200);
        assertThat(key()).isEqualTo(transaction);
        assertThat(savedStatus()).isEqualTo("CONFIRMED");
        assertThat(stock()).isEqualTo(3);
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        assertThat(pg.state().counts().lookupByOrderReceived()).isEqualTo(1);
    }

    @Test
    void explicitCallbacksUseActualControllerAndDuplicateFailureReleasesOnce() throws Exception {
        confirm();
        pg.setStatus(key(), PaymentStatus.FAILED);
        var batch = pg.sendCallbacks(key(), 3).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(batch.deliveries()).hasSize(3).allSatisfy(delivery -> assertThat(delivery.httpStatus()).isEqualTo(200));
        assertThat(savedStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(histories("ORDER_RELEASE")).isEqualTo(1);
        assertThat(stock()).isEqualTo(5);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(3);
    }

    @Test
    void callbackStatusIsOnlyNotificationAndHasNoCustomerHeaderOrData() {
        confirm();
        ResponseEntity<JsonNode> response = request("/api/v1/payments/callback", HttpMethod.POST,
            Map.of("providerOrderId", PaymentModel.providerOrderIdOf(orderId), "transactionKey", key(), "status", "SUCCESS"), null);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(savedStatus()).isEqualTo("PAYMENT_PENDING");
        pg.setStatus(key(), PaymentStatus.SUCCESS);
        response = request("/api/v1/payments/callback", HttpMethod.POST,
            Map.of("providerOrderId", PaymentModel.providerOrderIdOf(orderId), "transactionKey", key(), "status", "FAILED"), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(savedStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void forgedCallbackDoesNotBindUnknownKeyOrApplyKnownOtherOrdersTransaction() {
        confirm();
        String originalKey = key();
        var unknown = request("/api/v1/payments/callback", HttpMethod.POST,
            Map.of("providerOrderId", PaymentModel.providerOrderIdOf(orderId), "transactionKey", "unknown_key", "status", "SUCCESS"), null);
        assertThat(unknown.getStatusCode().value()).isEqualTo(202);
        assertThat(key()).isEqualTo(originalKey);
        Long other = orders.create(userId, List.of(new OrderItemCommand(product.getId(), 1L))).id();
        success(other);
        request("/api/v1/orders/" + other + "/confirm", HttpMethod.POST, PgCommands.forOrder(other), userId);
        String otherKey = jdbc.queryForObject("SELECT transaction_key FROM payment WHERE order_id = ?", String.class, other);
        var mismatch = request("/api/v1/payments/callback", HttpMethod.POST,
            Map.of("providerOrderId", PaymentModel.providerOrderIdOf(orderId), "transactionKey", otherKey, "status", "SUCCESS"), null);
        assertThat(mismatch.getStatusCode().value()).isEqualTo(409);
        assertThat(mismatch.getBody().path("meta").path("errorCode").asText()).isEqualTo("PAYMENT_RESULT_CONFLICT");
        assertThat(key()).isEqualTo(originalKey);
        assertThat(savedStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForObject("SELECT code FROM payment_conflict", String.class)).isEqualTo("IDENTITY_MISMATCH");
        assertThat(histories("ORDER_RELEASE")).isZero();
    }

    @Test
    void terminalReconcileFailureReturns202WithoutUndoingSuccess() {
        success(orderId);
        confirm();
        pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key(), ResponsePlan.raw(500, "", null));
        ResponseEntity<JsonNode> response = reconcile();
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(response.getBody().path("data").path("payment").path("verificationStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(stock()).isEqualTo(3);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(1);
    }

    @Test
    void terminalPendingIsConflictAndSummaryCommitsBefore409() {
        success(orderId);
        confirm();
        pg.setStatus(key(), PaymentStatus.PENDING);
        assertThat(reconcile().getStatusCode().value()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT code FROM payment_conflict", String.class)).isEqualTo("TERMINAL_REGRESSION");
        assertThat(savedStatus()).isEqualTo("CONFIRMED");
        assertThat(stock()).isEqualTo(3);
    }

    @Test
    void createCircuitBlockLeavesDraftAndRecoveryAllowsFirstAttempt() {
        circuits.circuitBreaker("pgCreate").transitionToOpenState();
        var response = confirm();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo("PG_CREATE_UNAVAILABLE");
        assertThat(savedStatus()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class)).isZero();
        assertThat(stock()).isEqualTo(5);
        assertThat(histories("ORDER_RESERVATION")).isZero();
        assertThat(pg.state().counts().createReceived()).isZero();
        circuits.circuitBreaker("pgCreate").reset();
        assertThat(confirm().getStatusCode().value()).isEqualTo(202);
        circuits.circuitBreaker("pgCreate").transitionToOpenState();
        assertThat(confirm().getStatusCode().value()).isEqualTo(202);
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
    }

    @Test
    void invalidConfirmAndDifferentKeyCannotMakeAdditionalEffects() {
        assertThat(request(orderPath() + "/confirm", HttpMethod.POST, null, userId).getStatusCode().value()).isEqualTo(400);
        assertThat(request(orderPath() + "/confirm", HttpMethod.POST,
            new PaymentConfirmCommand("invalid", "TEST", "0000-0000-0000-0000"), userId).getStatusCode().value()).isEqualTo(400);
        assertThat(pg.state().counts().createReceived()).isZero();
        confirm();
        var otherKey = new PaymentConfirmCommand("123e4567-e89b-12d3-a456-426614174000", "TEST", "0000-0000-0000-0000");
        assertThat(request(orderPath() + "/confirm", HttpMethod.POST, otherKey, userId).getStatusCode().value()).isEqualTo(409);
        assertThat(histories("ORDER_RESERVATION")).isEqualTo(1);
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
    }

    @Test
    void ownershipAndMissingAttemptFailBeforePgIo() {
        Long other = users.createUserWithoutPoint().getId();
        assertThat(request(orderPath() + "/confirm", HttpMethod.POST, null, other).getStatusCode().value()).isEqualTo(404);
        assertThat(request(orderPath() + "/payment/reconcile", HttpMethod.POST, null, other).getStatusCode().value()).isEqualTo(404);
        assertThat(reconcile().getStatusCode().value()).isEqualTo(404);
        assertThat(request(orderPath() + "/confirm", HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(400);
        assertThat(pg.state().journal()).isEmpty();
    }

    @Test
    void malformedAndUnknownCallbacksDoNotQueryPg() {
        assertThat(request("/api/v1/payments/callback", HttpMethod.POST, Map.of(), null).getStatusCode().value()).isEqualTo(400);
        assertThat(request("/api/v1/payments/callback", HttpMethod.POST,
            Map.of("providerOrderId", "LP-ORD-0000000042", "transactionKey", "bad/key", "status", "SUCCESS"), null)
            .getStatusCode().value()).isEqualTo(400);
        assertThat(request("/api/v1/payments/callback", HttpMethod.POST,
            Map.of("providerOrderId", "LP-ORD-0000000042", "transactionKey", "valid_key", "status", "SUCCESS"), null)
            .getStatusCode().value()).isEqualTo(404);
        assertThat(pg.state().journal()).isEmpty();
    }

    @Test
    void pendingThenExplicitSuccessCallbackConfirmsWithoutAdditionalStockDeduction() throws Exception {
        confirm();
        pg.setStatus(key(), PaymentStatus.SUCCESS);
        var result = pg.sendCallbacks(key(), 1).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(result.deliveries()).singleElement().satisfies(delivery -> assertThat(delivery.httpStatus()).isEqualTo(200));
        JsonNode data = request(orderPath(), HttpMethod.GET, null, userId).getBody().path("data");
        assertThat(data.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(data.path("paidAmount").asLong()).isEqualTo(4000);
        assertThat(data.path("payment").path("verificationStatus").asText()).isEqualTo("VERIFIED");
        assertThat(stock()).isEqualTo(3);
        assertThat(histories("ORDER_RESERVATION")).isEqualTo(1);
        assertThat(histories("ORDER_RELEASE")).isZero();
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
    }

    @Test
    void reconcilePendingReturns202AndOnlyOneLookup() {
        confirm();
        var result = reconcile();
        assertThat(result.getStatusCode().value()).isEqualTo(202);
        assertThat(result.getBody().path("data").path("payment").path("status").asText()).isEqualTo("PENDING");
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(1);
        assertThat(pg.state().counts().lookupByOrderReceived()).isZero();
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        assertThat(stock()).isEqualTo(3);
    }
}
