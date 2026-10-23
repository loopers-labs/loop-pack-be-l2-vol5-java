package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderPaymentStockService;
import com.loopers.application.order.OrderService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Disabled means no PG bean exists. Existing terminal facts still remain available without external verification. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PgPaymentRecoveryDisabledApiE2ETest {
    @Autowired private TestRestTemplate rest;
    @Autowired private OrderService orders;
    @Autowired private OrderPaymentStockService stocks;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectProvider<PaymentGateway> gateways;

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    private long orderId;
    private UUID attemptKey;

    @BeforeEach
    void setUp() {
        SimpleClientHttpRequestFactory singleRequest = new SimpleClientHttpRequestFactory();
        singleRequest.setConnectTimeout(Duration.ofSeconds(2));
        singleRequest.setReadTimeout(Duration.ofSeconds(10));
        rest.getRestTemplate().setRequestFactory(singleRequest);
        assertThat(gateways.getIfAvailable()).isNull();
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        Product product = products.save(new Product(brands.save(new Brand("브랜드")), "상품", 100, 5));
        orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        attemptKey = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        cleanup.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_OBSERVED", "PENDING", "UNKNOWN"})
    void unfinishedReconcileCannotVerifyWithPgDisabledAndNeverChangesFacts(String status) {
        prepare(status);
        var before = state();

        failure(post(reconcilePath(), "alice", null), 503, "PAYMENT_PROVIDER_UNAVAILABLE");

        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void terminalReconcileReturnsExistingResultEvenWithNoGatewayAndNoFingerprint(String status) {
        prepare(status);
        jdbc.update("UPDATE payment_attempt SET request_fingerprint=NULL, fingerprint_version=NULL, key_version=NULL WHERE order_id=?", orderId);
        var before = state();

        ResponseEntity<JsonNode> response = post(reconcilePath(), "alice", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("orderId").asLong()).isEqualTo(orderId);
        assertThat(data.path("payment").path("status").asText()).isEqualTo(status);
        assertThat(data.path("payment").path("paymentAttemptKey").asText()).isEqualTo(attemptKey.toString());
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void disabledPgDoesNotMaskReconcileOwnershipUnstartedOrHistoricalPointErrors() {
        var beforeDraft = state();
        failure(post(reconcilePath(), "bob", null), 404, "ORDER_NOT_FOUND");
        failure(post(reconcilePath(), "alice", null), 409, "PAYMENT_NOT_STARTED");
        assertThat(state()).isEqualTo(beforeDraft);

        orders.confirm("alice", orderId);
        var beforePoint = state();
        failure(post(reconcilePath(), "alice", null), 409, "ORDER_ALREADY_CONFIRMED");
        assertThat(state()).isEqualTo(beforePoint);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUCCESS", "FAILED"})
    void callbackCannotAcknowledgeUnverifiedHintWhenPgIsDisabled(String status) {
        prepare(status);
        var before = state();

        failure(post("/api/v1/payments/callback", null, "{\"providerOrderId\":\"" + providerId()
            + "\",\"transactionKey\":\"STORED-PG-TX\",\"status\":\"SUCCESS\"}"), 503, "PAYMENT_PROVIDER_UNAVAILABLE");

        assertThat(state()).isEqualTo(before);
    }

    private void prepare(String status) {
        stocks.prepareNew("alice", orderId, attemptKey, "135135", providerId(), "TEST", "0000-0000-0000-0000");
        if (status.equals("UNKNOWN")) {
            stocks.markUnknown(orderId, attemptKey);
        } else if (!status.equals("NOT_OBSERVED")) {
            stocks.apply(orderId, attemptKey, new PaymentTransaction("135135", providerId(), "STORED-PG-TX",
                200, PaymentTransaction.Status.valueOf(status)));
        }
    }

    private String providerId() {
        return "LP-ORD-%010d".formatted(orderId);
    }

    private String reconcilePath() {
        return "/api/v1/orders/" + orderId + "/payment/reconcile";
    }

    private ResponseEntity<JsonNode> post(String path, String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void failure(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
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
}
