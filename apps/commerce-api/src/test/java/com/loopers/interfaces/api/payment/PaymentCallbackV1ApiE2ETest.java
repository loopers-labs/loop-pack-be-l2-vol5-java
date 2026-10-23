package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentRecoveryJob;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.CallbackInboxStatus;
import com.loopers.domain.payment.PaymentCallbackInbox;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentCallbackInboxJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.wiremock.webhooks.Webhooks.webhook;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentCallbackV1ApiE2ETest {

    private static final String CALLBACK_URL = "/api/v1/payments/callback";
    private static final ParameterizedTypeReference<ApiResponse<Object>> RESPONSE_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER_RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private PaymentCallbackInboxJpaRepository paymentCallbackInboxJpaRepository;

    @Autowired
    private PaymentRecoveryJob paymentRecoveryJob;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResponseEntity<ApiResponse<Object>> postCallback(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return testRestTemplate.exchange(CALLBACK_URL, HttpMethod.POST, new HttpEntity<>(body, headers), RESPONSE_TYPE);
    }

    @DisplayName("POST /api/v1/payments/callback — 받은 본문을 원문 그대로 저장하고 200을 반환한다.")
    @Test
    void returns200_andStoresRawPayload() {
        // act
        ResponseEntity<ApiResponse<Object>> response = postCallback(
            "{\"transactionKey\":\"TEST-TX-1\",\"providerOrderId\":\"LP-ORD-0000000001\",\"status\":\"SUCCESS\"}");

        // assert
        List<PaymentCallbackInbox> rows = paymentCallbackInboxJpaRepository.findAll();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(CallbackInboxStatus.RECEIVED);
        assertThat(rows.get(0).getProviderOrderId()).isEqualTo("LP-ORD-0000000001");
    }

    @DisplayName("본문이 JSON이 아니어도 200을 반환하고 원문을 저장한다 — 200은 \"받아서 저장했다\"는 뜻이다.")
    @Test
    void returns200_evenForBrokenPayload() {
        ResponseEntity<ApiResponse<Object>> response = postCallback("not-json");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentCallbackInboxJpaRepository.findAll()).extracting(PaymentCallbackInbox::getRawPayload)
            .containsExactly("not-json");
    }

    @DisplayName("테스트 PG가 거래를 수락한 뒤 실제로 callback을 보내면, 수신함에 쌓이고 회차가 PG 조회로 주문을 확정한다.")
    @Test
    void realCallbackFromPg_confirmsOrderAfterRecoveryRun() throws InterruptedException {
        // arrange — 생성 요청을 202로 받고, 응답 뒤 우리 callback 주소로 결과를 알리는 테스트 PG
        Long userId = userJpaRepository.save(new UserModel()).getId();
        Long productId = productJpaRepository.save(new ProductModel("상품", 3_000L, 1L, 10)).getId();
        TestPg.server().stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH))
            .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                .withBody("{\"transactionKey\":\"TEST-TX-{{jsonPath request.body '$.orderId'}}\","
                    + "\"orderId\":\"{{jsonPath request.body '$.orderId'}}\","
                    + "\"amount\":{{jsonPath request.body '$.amount'}},\"status\":\"PENDING\"}")
                .withTransformers("response-template"))
            .withServeEventListener("webhook", webhook()
                .withMethod("POST")
                .withUrl("http://localhost:" + port + CALLBACK_URL)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"transactionKey\":\"TEST-TX-{{jsonPath originalRequest.body '$.orderId'}}\","
                    + "\"providerOrderId\":\"{{jsonPath originalRequest.body '$.orderId'}}\",\"status\":\"SUCCESS\"}")));
        TestPg.server().stubFor(get(urlPathMatching(TestPg.PAYMENTS_PATH + "/TEST-TX-.*"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"transactionKey\":\"{{request.pathSegments.[3]}}\","
                    + "\"orderId\":\"{{regexExtract request.pathSegments.[3] 'LP-ORD-[0-9]+'}}\",\"amount\":3000,\"status\":\"SUCCESS\"}")
                .withTransformers("response-template")));

        Long orderId = testRestTemplate.exchange("/api/v1/orders", HttpMethod.POST,
            jsonWithUser(userId, Map.of("items", List.of(Map.of("productId", productId, "quantity", 1)))), ORDER_RESPONSE_TYPE)
            .getBody().data().id();

        // act — 확정(PG 결제 시작) → PG가 callback 전송 → 회차 실행
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmed = testRestTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            jsonWithUser(userId, Map.of("paymentAttemptKey", "key-1", "cardType", "TEST", "cardNo", "0000-0000-0000-0000")),
            ORDER_RESPONSE_TYPE);
        awaitInboxRows(1);
        paymentRecoveryJob.runOnce(ZonedDateTime.now().plusSeconds(1));

        // assert
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(paymentCallbackInboxJpaRepository.findAll()).extracting(PaymentCallbackInbox::getStatus)
            .containsExactly(CallbackInboxStatus.DONE);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @DisplayName("위조된 callback이 controller로 SUCCESS를 알려도, PG 조회가 처리중이면 주문은 확정되지 않는다 — 본문만으로 성공하지 않는다.")
    @Test
    void forgedSuccessCallback_doesNotConfirmOrder() {
        // arrange — 결제는 PG에서 아직 처리중(PENDING)이다
        Long userId = userJpaRepository.save(new UserModel()).getId();
        Long productId = productJpaRepository.save(new ProductModel("상품", 3_000L, 1L, 10)).getId();
        Long orderId = testRestTemplate.exchange("/api/v1/orders", HttpMethod.POST,
            jsonWithUser(userId, Map.of("items", List.of(Map.of("productId", productId, "quantity", 1)))), ORDER_RESPONSE_TYPE)
            .getBody().data().id();
        testRestTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            jsonWithUser(userId, Map.of("paymentAttemptKey", "key-1", "cardType", "TEST", "cardNo", "0000-0000-0000-0000")),
            ORDER_RESPONSE_TYPE);
        String providerOrderId = String.format("LP-ORD-%010d", orderId);
        String transactionKey = TestPg.transactionKeyOf(providerOrderId);
        TestPg.server().stubFor(get(urlPathMatching(TestPg.PAYMENTS_PATH + "/" + transactionKey))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"transactionKey\":\"" + transactionKey + "\",\"orderId\":\"" + providerOrderId
                    + "\",\"amount\":3000,\"status\":\"PENDING\"}")));

        // act — 누군가 SUCCESS라고 알린다
        ResponseEntity<ApiResponse<Object>> response = postCallback("{\"transactionKey\":\"" + transactionKey
            + "\",\"providerOrderId\":\"" + providerOrderId + "\",\"status\":\"SUCCESS\"}");
        paymentRecoveryJob.runOnce(ZonedDateTime.now().plusSeconds(1));

        // assert — 받기는 했지만(200) 확정은 PG 조회 결과(PENDING)를 따른다
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentCallbackInboxJpaRepository.findAll()).extracting(PaymentCallbackInbox::getStatus)
            .containsExactly(CallbackInboxStatus.DONE);
    }

    private HttpEntity<Object> jsonWithUser(Long userId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /** webhook은 PG 응답 뒤 비동기로 나간다 — 수신함에 쌓일 때까지 기다린다. */
    private void awaitInboxRows(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (paymentCallbackInboxJpaRepository.count() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(paymentCallbackInboxJpaRepository.count()).isEqualTo(expected);
    }
}
