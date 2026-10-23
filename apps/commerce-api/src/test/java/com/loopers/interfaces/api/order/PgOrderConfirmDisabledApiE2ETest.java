package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderService;
import com.loopers.application.order.OrderPaymentStockService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Default-off PG must not silently fall back to charging points. Uses only the isolated test DB. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PgOrderConfirmDisabledApiE2ETest {
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
        assertThat(gateways.getIfAvailable()).as("PG is disabled, not a successful gateway stub").isNull();
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

    @Test
    void validNewPaymentReturns503WithoutPointFallbackOrReservation() {
        var before = state();

        failure(confirm("alice", "{\"paymentAttemptKey\":\"12345678-abcd-4abc-8abc-123456789abc\","
            + "\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}"),
            503, "PAYMENT_PROVIDER_UNAVAILABLE", "현재 결제 서비스를 사용할 수 없습니다.");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void historicalPointAndOwnershipErrorsDoNotDependOnPgAvailability() {
        orders.confirm("alice", orderId);
        var before = state();

        failure(confirm("alice", null), 409, "ORDER_ALREADY_CONFIRMED", "이미 확정된 주문입니다.");
        failure(confirm("bob", null), 404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void missingPgBodyIsInvalidInsteadOfSilentlyChargingPoints() {
        var before = state();

        failure(confirm("alice", null), 400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");

        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"NOT_OBSERVED,202", "PENDING,202", "UNKNOWN,202", "SUCCESS,200", "FAILED,200"})
    void matchingExistingAttemptReturnsStoredResultEvenWhenPgSendingIsDisabled(String status, int httpStatus) {
        prepareStoredAttempt(status);
        var before = state();

        ResponseEntity<JsonNode> response = confirm("alice", paymentBody(attemptKey, "TEST", "0000-0000-0000-0000"));

        assertThat(response.getStatusCode().value()).isEqualTo(httpStatus);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("orderId").asLong()).isEqualTo(orderId);
        assertThat(data.path("paymentMethod").asText()).isEqualTo("PG");
        assertThat(data.path("payment").path("paymentAttemptKey").asText()).isEqualTo(attemptKey.toString());
        assertThat(data.path("payment").path("status").asText()).isEqualTo(status);
        assertThat(data.path("links").path("order").asText()).isEqualTo("/api/v1/orders/" + orderId);
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"card", "type", "key"})
    void disabledPgDoesNotMaskExistingRequestConflicts(String difference) {
        prepareStoredAttempt("SUCCESS");
        var before = state();

        failure(confirm("alice", paymentBody(difference.equals("key") ? UUID.randomUUID() : attemptKey,
            difference.equals("type") ? "OTHER_TEST" : "TEST",
            difference.equals("card") ? "1111-1111-1111-1111" : "0000-0000-0000-0000")),
            409, "PAYMENT_REQUEST_CONFLICT", "기존 결제 시도와 일치하지 않는 요청입니다.");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void disabledPgDoesNotMaskLegacyFingerprintUnverifiability() {
        prepareStoredAttempt("PENDING");
        jdbc.update("UPDATE payment_attempt SET request_fingerprint=NULL, fingerprint_version=NULL, key_version=NULL WHERE order_id=?", orderId);
        var before = state();

        failure(confirm("alice", paymentBody(attemptKey, "TEST", "0000-0000-0000-0000")),
            409, "PAYMENT_REQUEST_UNVERIFIABLE", "기존 결제 요청과 비교할 정보가 없습니다. 주문 조회 또는 결제 결과 재확인을 이용해 주세요.");

        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"key_version", "fingerprint_version"})
    void disabledPgStillReportsUnavailableVerificationRatherThanUnavailableSending(String column) {
        prepareStoredAttempt("PENDING");
        jdbc.update("UPDATE payment_attempt SET " + column + "=999 WHERE order_id=?", orderId);
        var before = state();

        failure(confirm("alice", paymentBody(attemptKey, "TEST", "0000-0000-0000-0000")),
            503, "PAYMENT_VERIFICATION_UNAVAILABLE", "현재 결제 요청을 확인할 수 없습니다.");

        assertThat(state()).isEqualTo(before);
    }

    private void prepareStoredAttempt(String status) {
        // Committed records from an earlier enabled run. This fixture performs no HTTP and never sends a PG create.
        String providerId = "LP-ORD-%010d".formatted(orderId);
        stocks.prepareNew("alice", orderId, attemptKey, "135135", providerId, "TEST", "0000-0000-0000-0000");
        if (status.equals("UNKNOWN")) {
            stocks.markUnknown(orderId, attemptKey);
        } else if (!status.equals("NOT_OBSERVED")) {
            stocks.apply(orderId, attemptKey, new PaymentTransaction("135135", providerId, "DISABLED-PG-TX",
                200, PaymentTransaction.Status.valueOf(status)));
        }
    }

    private String paymentBody(UUID key, String type, String card) {
        return "{\"paymentAttemptKey\":\"" + key + "\",\"cardType\":\"" + type + "\",\"cardNo\":\"" + card + "\"}";
    }

    private ResponseEntity<JsonNode> confirm(String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", requester);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void failure(ResponseEntity<JsonNode> response, int status, String code, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().path("meta").path("message").asText()).isEqualTo(message);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private Map<String, List<Map<String, Object>>> state() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT id,order_id,HEX(attempt_key) AS attempt_key,status,transaction_key,provider_order_id,"
                + "HEX(request_fingerprint) AS digest,fingerprint_version,key_version,send_started_at FROM payment_attempt ORDER BY id"));
    }
}
