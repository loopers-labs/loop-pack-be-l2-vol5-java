package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentFinalResult;
import com.loopers.domain.payment.PaymentResultService;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/v1/orders/{orderId}/confirm 의 같은 키 재요청 응답(202 진행 중 / 200 종결)을 실제 HTTP 로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakePaymentGatewayConfig.class)
class OrderConfirmV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String ENDPOINT_ORDERS = "/api/v1/orders";
    private static final String CARD_NO = "0000-0000-0000-0000";

    private final TestRestTemplate testRestTemplate;
    private final OrderFacade orderFacade;
    private final PaymentResultService paymentResultService;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderConfirmV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        OrderFacade orderFacade,
        PaymentResultService paymentResultService,
        FakePaymentGateway fakePaymentGateway,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        PaymentAttemptJpaRepository paymentAttemptJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.orderFacade = orderFacade;
        this.paymentResultService = paymentResultService;
        this.fakePaymentGateway = fakePaymentGateway;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.paymentAttemptJpaRepository = paymentAttemptJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm 에 같은 키로 다시 요청할 때, ")
    @Nested
    class Reconfirm {
        @DisplayName("시도가 진행 중이면, 202 와 저장된 주문·시도 상태·조회 경로를 받고 create 는 추가로 호출되지 않는다.")
        @Test
        void returnsAcceptedWithStoredState_whenAttemptIsInProgress() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.ConfirmResponse>> first = confirm(userId, orderId, "k1");
            ResponseEntity<ApiResponse<OrderV1Dto.ConfirmResponse>> again = confirm(userId, orderId, "k1");

            // assert
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(first.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
            OrderV1Dto.ConfirmResponse data = first.getBody().data();
            assertThat(data.orderId()).isEqualTo(orderId);
            assertThat(data.orderStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(data.attemptStatus()).isEqualTo("PENDING");
            assertThat(data.links().order()).isEqualTo(ENDPOINT_ORDERS + "/" + orderId);
            assertThat(data.links().reconcile()).isEqualTo(ENDPOINT_ORDERS + "/" + orderId + "/payment/reconcile");

            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(again.getBody().data()).isEqualTo(data);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
            assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
        }

        @DisplayName("시도가 SUCCESS 로 종결됐으면, 200 과 저장된 결과를 받고 create 는 추가로 호출되지 않으며 응답에 transactionKey·카드 번호가 없다.")
        @Test
        void returnsOkWithStoredResult_whenAttemptIsSettled() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            assertThat(confirm(userId, orderId, "k1").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            paymentResultService.applyFinal(attempt.getId(), PaymentFinalResult.success(attempt.getTransactionKey()));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.ConfirmResponse>> response = confirm(userId, orderId, "k1");
            ResponseEntity<String> raw = confirmRaw(userId, orderId, "k1");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().orderStatus()).isEqualTo("CONFIRMED");
            assertThat(response.getBody().data().attemptStatus()).isEqualTo("SUCCESS");
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(raw.getBody()).doesNotContain(attempt.getTransactionKey());
            assertThat(raw.getBody()).doesNotContain(CARD_NO);
        }
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.ConfirmResponse>> confirm(Long userId, Long orderId, String attemptKey) {
        return testRestTemplate.exchange(
            ENDPOINT_ORDERS + "/" + orderId + "/confirm",
            HttpMethod.POST,
            new HttpEntity<>(confirmBody(attemptKey), headers(userId)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<String> confirmRaw(Long userId, Long orderId, String attemptKey) {
        return testRestTemplate.exchange(
            ENDPOINT_ORDERS + "/" + orderId + "/confirm",
            HttpMethod.POST,
            new HttpEntity<>(confirmBody(attemptKey), headers(userId)),
            String.class
        );
    }

    private static String confirmBody(String attemptKey) {
        return "{\"paymentAttemptKey\":\"" + attemptKey + "\",\"cardType\":\"TEST\",\"cardNo\":\"" + CARD_NO + "\"}";
    }

    private static HttpHeaders headers(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(USER_ID_HEADER, String.valueOf(userId));
        return headers;
    }

    private Long saveUser() {
        return userJpaRepository.save(new User()).getId();
    }

    private Long saveProduct(long price, int stock) {
        Product product = new Product(1L, "상품", new Price(price));
        product.changeStock(stock);
        return productJpaRepository.save(product).getId();
    }

    private Long createOrder(Long userId, Long productId, int quantity) {
        return orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productId, quantity))).orderId();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }
}