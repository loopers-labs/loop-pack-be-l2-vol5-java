package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.interfaces.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 알림 API({@code POST /api/v1/payments/callback})의 HTTP 계약을 실제 요청으로 확인한다. 알림은 PG 가 보내는 요청이라
 * 고객 식별 헤더 없이 처리하고, <b>알림 본문만으로는 주문이 확정되지 않는다</b>. 조작한 본문(성공이라고 주장하는 알림)이
 * 효과를 내지 못하는지가 핵심이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentCallbackApiE2ETest extends PaymentFlowTestSupport {

    private static final String CALLBACK_URL = "/api/v1/payments/callback";
    private static final String START_BODY =
        "{\"paymentAttemptKey\":\"attempt-1\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}";

    @Autowired
    private TestRestTemplate testRestTemplate;

    private User user;
    private Product product;
    private Long orderId;

    // 결제를 시작해 PG 가 거래를 수락한 상태로 만든다. 3,500 x 2 = 7,000, 재고 5 → 3.
    private void startAccepted() {
        user = saveUser(10_000);
        product = saveProduct("티셔츠", 3_500, 5);
        orderId = placeOrder(user, product, 2);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", String.valueOf(user.getId()));
        testRestTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST, new HttpEntity<>(START_BODY, headers), String.class
        );
    }

    private String body(String providerOrderId, String transactionKey, String status) {
        return "{\"providerOrderId\":\"" + providerOrderId + "\",\"transactionKey\":\"" + transactionKey
            + "\",\"status\":\"" + status + "\"}";
    }

    // 고객 식별 헤더(X-USER-ID)를 일부러 보내지 않는다. PG 가 보내는 요청이기 때문이다.
    private ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> callback(String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return testRestTemplate.exchange(
            CALLBACK_URL, HttpMethod.POST, new HttpEntity<>(jsonBody, headers),
            new ParameterizedTypeReference<>() {}
        );
    }

    @DisplayName("PG 가 성공을 알리고 실제로도 SUCCESS 면, 식별 헤더 없이 200 으로 확정한다. 재고는 추가로 차감되지 않는다.")
    @Test
    void confirms_whenPgReallySucceeded() {
        // arrange
        startAccepted();
        String key = pgKeyOf(orderId);
        pg.setStatus(key, "SUCCESS");

        // act
        ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> response =
            callback(body(providerOrderIdOf(orderId), key, "SUCCESS"));

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaymentDto.CallbackResponse data = response.getBody().data();
        assertThat(data.orderId()).isEqualTo(orderId);
        assertThat(data.orderStatus()).isEqualTo("CONFIRMED");
        assertThat(data.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(data.lookupUnavailable()).isNull();
        assertThat(stockOf(product)).isEqualTo(3);
        assertThat(balanceOf(user)).isEqualTo(10_000L);
    }

    @DisplayName("성공이라고 주장하는 위조 알림은 PG 가 아직 PENDING 이면 아무것도 확정하지 못한다.")
    @Test
    void doesNotConfirm_whenForgedSuccessCallbackAndPgIsPending() {
        // arrange
        startAccepted();

        // act
        ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> response =
            callback(body(providerOrderIdOf(orderId), pgKeyOf(orderId), "SUCCESS"));

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().orderStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(stockOf(product)).isEqualTo(3);
    }

    @DisplayName("알림의 status 는 쓰지 않는다. 실패라고 알려도 PG 가 SUCCESS 면 SUCCESS 를 따르고, 반대도 마찬가지다.")
    @Test
    void followsPgNotBodyStatus() {
        // arrange
        startAccepted();
        String key = pgKeyOf(orderId);
        pg.setStatus(key, "SUCCESS");

        // act: 본문은 FAILED 라고 주장한다.
        ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> response =
            callback(body(providerOrderIdOf(orderId), key, "FAILED"));

        // assert
        assertThat(response.getBody().data().orderStatus()).isEqualTo("CONFIRMED");
        assertThat(stockOf(product)).isEqualTo(3);
    }

    @DisplayName("같은 알림을 여러 번 받아도 재고는 한 번만 돌아온다.")
    @Test
    void returnsStockOnce_whenFailureCallbackIsDuplicated() {
        // arrange
        startAccepted();
        String key = pgKeyOf(orderId);
        pg.setStatus(key, "FAILED");
        String request = body(providerOrderIdOf(orderId), key, "FAILED");

        // act
        ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> first = callback(request);
        callback(request);
        callback(request);

        // assert
        assertThat(first.getBody().data().orderStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(stockOf(product)).isEqualTo(5);
    }

    @DisplayName("다른 주문의 거래 키를 담은 위조 알림은 적용하지 않는다.")
    @Test
    void doesNotApplyOtherOrdersTransaction() {
        // arrange
        startAccepted();
        String otherKey = pg.addTransaction("LP-ORD-0000099999", 7_000, "SUCCESS").key();

        // act
        ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> response =
            callback(body(providerOrderIdOf(orderId), otherKey, "SUCCESS"));

        // assert
        assertThat(response.getBody().data().orderStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(attemptOf(orderId).get("anomaly")).isEqualTo("MISMATCH");
        assertThat(stockOf(product)).isEqualTo(3);
    }

    @DisplayName("PG 를 조회하지 못하면 200 과 함께 lookupUnavailable 을 알리고 아무것도 정하지 않는다.")
    @Test
    void leavesState_whenPgCannotBeQueried() {
        // arrange
        startAccepted();
        String key = pgKeyOf(orderId);
        pg.setStatus(key, "SUCCESS");
        pg.failLookups(500);

        // act
        ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> response =
            callback(body(providerOrderIdOf(orderId), key, "SUCCESS"));

        // assert: PG 가 성공이라 해도 확인하지 못했으므로 확정하지 않는다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().lookupUnavailable()).isTrue();
        assertThat(response.getBody().data().orderStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
    }

    @DisplayName("모르는 providerOrderId 의 알림은 404 NOT_FOUND 이고 아무 효과도 없다.")
    @Test
    void returnsNotFound_whenProviderOrderIdIsUnknown() {
        // arrange
        startAccepted();
        pg.setStatus(pgKeyOf(orderId), "SUCCESS");

        // act
        ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> response =
            callback(body("LP-ORD-0000099999", pgKeyOf(orderId), "SUCCESS"));

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("NOT_FOUND");
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
    }

    @DisplayName("본문의 필수 값이 빠지거나 비어 있으면 400 INVALID_INPUT 이다.")
    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "{}",
        "{\"transactionKey\":\"TEST-TX-0001\",\"status\":\"SUCCESS\"}",
        "{\"providerOrderId\":\"LP-ORD-0000000001\",\"status\":\"SUCCESS\"}",
        "{\"providerOrderId\":\"LP-ORD-0000000001\",\"transactionKey\":\"TEST-TX-0001\"}",
        "{\"providerOrderId\":\"\",\"transactionKey\":\"TEST-TX-0001\",\"status\":\"SUCCESS\"}"
    })
    void returnsBadRequest_whenBodyIsInvalid(String jsonBody) {
        // act
        ResponseEntity<ApiResponse<PaymentDto.CallbackResponse>> response = callback(jsonBody);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(pg.lookupByKeyCalls()).isZero();
    }
}
