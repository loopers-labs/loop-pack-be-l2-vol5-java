package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

import static com.loopers.support.FakePaymentGateway.pending;
import static org.assertj.core.api.Assertions.assertThat;

/** POST /api/v1/payments/callback 의 HTTP 계약. 테스트가 PG 역할로 callback 을 보낸다 (X-USER-ID 없음). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentCallbackV1ApiE2ETest {

    private static final String CALLBACK = "/api/v1/payments/callback";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FakePaymentGateway fakePg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ApiTestClient api;
    private UserModel user;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(restTemplate, objectMapper);
        user = fixtures.user();
        BrandModel brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1_000L, 5);
    }

    @AfterEach
    void tearDown() {
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    /** PG 가 PENDING 으로 접수한 결제 대기 주문 (거래 키 "tx-1"). */
    private OrderModel awaitingOrder(int quantity) {
        OrderModel order = fixtures.draftOrder(user.getId(), product, quantity);
        fakePg.willRespond(pending("tx-1"));
        api.post("/api/v1/orders/" + order.getId() + "/confirm", user.getId(),
            Map.of("paymentAttemptKey", "attempt-1", "cardType", "SAMSUNG", "cardNumber", "1234-5678-9814-1451"))
            .assertSuccess(HttpStatus.OK);
        return order;
    }

    private static Map<String, Object> callbackBody(String pgOrderId, Long amount, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("transactionKey", "tx-1");
        body.put("orderId", pgOrderId);
        body.put("amount", amount);
        body.put("status", status);
        return body;
    }

    private String statusOf(OrderModel order) {
        return api.get("/api/v1/orders/" + order.getId(), user.getId()).assertSuccess(HttpStatus.OK).data().path("status").asText();
    }

    @DisplayName("SUCCESS callback 은 200, 주문이 CONFIRMED 가 된다.")
    @Test
    void successCallback_confirmsOrder() {
        OrderModel order = awaitingOrder(2);

        api.post(CALLBACK, null, callbackBody("ORDER-" + order.getId(), 2_000L, "SUCCESS")).assertSuccess(HttpStatus.OK);

        assertThat(statusOf(order)).isEqualTo("CONFIRMED");
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
    }

    @DisplayName("FAILED callback 은 200, 주문이 FAILED 가 되고 재고가 돌아온다.")
    @Test
    void failedCallback_failsOrder() {
        OrderModel order = awaitingOrder(2);

        api.post(CALLBACK, null, callbackBody("ORDER-" + order.getId(), 2_000L, "FAILED")).assertSuccess(HttpStatus.OK);

        assertThat(statusOf(order)).isEqualTo("FAILED");
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(5);
    }

    @DisplayName("모르는 status 의 callback 도 200 으로 받되 주문은 AWAITING_PAYMENT 그대로.")
    @Test
    void unknownStatusCallback_keepsAwaiting() {
        OrderModel order = awaitingOrder(2);

        api.post(CALLBACK, null, callbackBody("ORDER-" + order.getId(), 2_000L, "CANCELED")).assertSuccess(HttpStatus.OK);

        assertThat(statusOf(order)).isEqualTo("AWAITING_PAYMENT");
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
    }

    @DisplayName("[ER-05 ORDER_NOT_FOUND] PG 주문 ID 형식이 아니거나 없는 주문이면 404.")
    @Test
    void orderNotFound() {
        api.post(CALLBACK, null, callbackBody("BAD-1", 1_000L, "SUCCESS")).assertError(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        api.post(CALLBACK, null, callbackBody("ORDER-999999", 1_000L, "SUCCESS")).assertError(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
    }
}
