package com.loopers.interfaces.api.order;

import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.pg.FakePgServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 결제 시작 API({@code POST /api/v1/orders/{orderId}/confirm})의 HTTP 계약을 실제 요청으로 확인한다. 처리 중이거나 결과를
 * 모르면 202, PG 결과로 끝난 시도는 200 이다. 202 는 성공도 실패도 아니라 아직 모른다는 뜻이다.
 *
 * <p>업무 규칙(원복, 오류 우선순위 등)은 {@code PaymentStartRulesTest}, 동시 요청은 {@code PaymentStartConcurrencyTest}
 * 가 확인한다. 여기서는 상태 코드, 응답 모양, 입력 검증, 요청자 식별과 같은 HTTP 경계를 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderPaymentApiE2ETest extends PaymentFlowTestSupport {

    private static final String ORDERS_URL = "/api/v1/orders";
    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String VALID_BODY =
        "{\"paymentAttemptKey\":\"attempt-1\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}";

    @Autowired
    private TestRestTemplate testRestTemplate;

    private HttpHeaders headers(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            headers.set(USER_ID_HEADER, userId);
        }
        return headers;
    }

    private ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> confirm(String userId, Object orderId, String body) {
        return testRestTemplate.exchange(
            ORDERS_URL + "/" + orderId + "/confirm", HttpMethod.POST, new HttpEntity<>(body, headers(userId)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> reconcile(String userId, Object orderId) {
        return testRestTemplate.exchange(
            ORDERS_URL + "/" + orderId + "/payment/reconcile", HttpMethod.POST, new HttpEntity<>(headers(userId)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<OrderDto.OrderResponse>> getDetail(String userId, Object orderId) {
        return testRestTemplate.exchange(
            ORDERS_URL + "/" + orderId, HttpMethod.GET, new HttpEntity<>(headers(userId)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private String idOf(User user) {
        return String.valueOf(user.getId());
    }

    // 거절된 요청은 주문을 DRAFT 로 두고 PG 를 호출하지 않으며 시도를 만들지 않는다.
    private void assertNothingStarted(Long orderId) {
        assertThat(orderStatusOf(orderId)).isEqualTo("DRAFT");
        assertThat(pg.createCalls()).isZero();
        assertThat(attemptCount()).isZero();
    }

    @DisplayName("결제를 시작하면,")
    @Nested
    class Started {
        @DisplayName("202 와 함께 PAYMENT_PENDING 주문, PENDING 시도, 조회 경로를 반환한다. 재고는 확보되고 포인트는 그대로다.")
        @Test
        void returnsAccepted_whenPgAcceptsTransaction() {
            // arrange: 3,500 x 2 = 7,000
            User user = saveUser(10_000);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = confirm(idOf(user), orderId, VALID_BODY);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            OrderDto.PaymentStartResponse data = response.getBody().data();
            assertThat(data.orderId()).isEqualTo(orderId);
            assertThat(data.orderStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(data.payment()).isEqualTo(new OrderDto.AttemptResponse("PENDING", 7_000L));
            assertThat(data.links().order()).isEqualTo("/api/v1/orders/" + orderId);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(stockOf(shirt)).isEqualTo(3);
            assertThat(balanceOf(user)).isEqualTo(10_000L);
        }

        @DisplayName("주문 상세는 PG 를 호출하지 않고 저장된 PAYMENT_PENDING 상태를 보여 준다. 결제 결과는 아직 없다.")
        @Test
        void detailShowsStoredPendingState_withoutCallingPg() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            confirm(idOf(user), orderId, VALID_BODY);
            int lookupsBefore = pg.lookupByKeyCalls() + pg.lookupByOrderCalls();

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> detail = getDetail(idOf(user), orderId);

            // assert
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(detail.getBody().data().status()).isEqualTo("PAYMENT_PENDING");
            assertThat(detail.getBody().data().payment()).isNull();
            assertThat(pg.lookupByKeyCalls() + pg.lookupByOrderCalls()).isEqualTo(lookupsBefore);
        }

        @DisplayName("PG 가 결과를 알려 주지 못해도 202 다. 시도는 UNKNOWN 이고 재고는 확보된 채 새 결제를 만들지 않는다.")
        @Test
        void returnsAccepted_whenResultIsUnknown() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            pg.onCreate(FakePgServer.acceptThenDropResponse());

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = confirm(idOf(user), orderId, VALID_BODY);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().data().orderStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(response.getBody().data().payment().status()).isEqualTo("UNKNOWN");
            assertThat(stockOf(shirt)).isEqualTo(3);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("같은 키로 다시 요청하면 같은 202 응답을 돌려주고 PG 에 다시 요청하지 않는다.")
        @Test
        void returnsSameResponse_whenSameKeyIsRepeated() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> first = confirm(idOf(user), orderId, VALID_BODY);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> second = confirm(idOf(user), orderId, VALID_BODY);

            // assert
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(second.getBody().data()).isEqualTo(first.getBody().data());
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(stockOf(shirt)).isEqualTo(3);
        }

        @DisplayName("PG 가 거래 생성 전에 확정 거절하면 200 이다. PAYMENT_FAILED 와 FAILED 를 돌려주고 재고는 돌아온다.")
        @Test
        void returnsOk_whenPgRejectedBeforeCreate() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            pg.onCreate(FakePgServer.rejectBeforeCreate());

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = confirm(idOf(user), orderId, VALID_BODY);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().orderStatus()).isEqualTo("PAYMENT_FAILED");
            assertThat(response.getBody().data().payment()).isEqualTo(new OrderDto.AttemptResponse("FAILED", 7_000L));
            assertThat(stockOf(shirt)).isEqualTo(5);
            assertThat(pg.transactionCount()).isZero();
        }

        @DisplayName("종결된 시도에 같은 키로 다시 요청하면 저장된 결과를 200 으로 돌려주고 재고·PG 에 변화가 없다.")
        @Test
        void returnsStoredResultAsOk_whenSettledAttemptIsRepeated() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            pg.onCreate(FakePgServer.rejectBeforeCreate());
            confirm(idOf(user), orderId, VALID_BODY);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> again = confirm(idOf(user), orderId, VALID_BODY);

            // assert
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(again.getBody().data().payment().status()).isEqualTo("FAILED");
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(stockOf(shirt)).isEqualTo(5);
        }

        @DisplayName("응답 본문 어디에도 카드 번호가 없다.")
        @Test
        void doesNotEchoCardNumber() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);

            // act
            ResponseEntity<String> raw = testRestTemplate.exchange(
                ORDERS_URL + "/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(VALID_BODY, headers(idOf(user))), String.class
            );

            // assert
            assertThat(raw.getBody()).isNotNull().doesNotContain(CARD_NO);
        }
    }

    @DisplayName("결제를 시작할 수 없으면,")
    @Nested
    class Rejected {
        @DisplayName("다른 키로 다시 요청하면 409 PAYMENT_ATTEMPT_CONFLICT 다. 두 번째 시도를 만들지 않는다.")
        @Test
        void returnsConflict_whenKeyDiffers() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            confirm(idOf(user), orderId, VALID_BODY);
            String otherKeyBody =
                "{\"paymentAttemptKey\":\"attempt-2\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}";

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = confirm(idOf(user), orderId, otherKeyBody);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().meta().errorCode()).isEqualTo("PAYMENT_ATTEMPT_CONFLICT");
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(attemptCount()).isEqualTo(1);
        }

        @DisplayName("재고가 부족하면 409 OUT_OF_STOCK 이다. 주문은 DRAFT 로 남고 PG 를 호출하지 않는다 (409 업무 오류의 대표).")
        @Test
        void returnsConflict_whenStockIsInsufficient() {
            // arrange: 주문을 만든 뒤 재고가 줄었다.
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            jdbcTemplate.update("update products set stock = 1 where id = ?", shirt.getId());

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = confirm(idOf(user), orderId, VALID_BODY);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().meta().errorCode()).isEqualTo("OUT_OF_STOCK");
            assertNothingStarted(orderId);
            assertThat(stockOf(shirt)).isEqualTo(1);
        }

        @DisplayName("없는 주문과 다른 사용자의 주문은 404 NOT_FOUND 이다.")
        @Test
        void returnsNotFound_whenOrderIsMissingOrBelongsToOtherUser() {
            // arrange
            User owner = saveUser(0);
            User other = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(owner, shirt, 1);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> missing = confirm(idOf(owner), 999_999, VALID_BODY);
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> otherUsers = confirm(idOf(other), orderId, VALID_BODY);

            // assert
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(otherUsers.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(otherUsers.getBody().meta().message()).isEqualTo(missing.getBody().meta().message());
            assertNothingStarted(orderId);
        }

        @DisplayName("orderId 가 숫자가 아니면 400 INVALID_INPUT 이다.")
        @Test
        void returnsBadRequest_whenOrderIdIsNotNumber() {
            // arrange
            User user = saveUser(0);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = confirm(idOf(user), "abc", VALID_BODY);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_INPUT");
            assertThat(pg.createCalls()).isZero();
        }

        @DisplayName("X-USER-ID 헤더가 없으면 400 INVALID_INPUT 이다 (식별 경로 등록 확인). PG 를 호출하지 않는다.")
        @Test
        void returnsBadRequest_whenHeaderIsMissing() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 1);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = confirm(null, orderId, VALID_BODY);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_INPUT");
            assertNothingStarted(orderId);
        }

        @DisplayName("본문의 필수 값이 빠지거나 형식이 틀리면 400 INVALID_INPUT 이다. 재고도 PG 도 건드리지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = {
            "",
            "{}",
            "{\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}",
            "{\"paymentAttemptKey\":\"\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}",
            "{\"paymentAttemptKey\":\"attempt-1\",\"cardNo\":\"0000-0000-0000-0000\"}",
            "{\"paymentAttemptKey\":\"attempt-1\",\"cardType\":\"TEST\"}",
            "{\"paymentAttemptKey\":\"attempt-1\",\"cardType\":\"TEST\",\"cardNo\":\"0000000000000000\"}",
            "{\"paymentAttemptKey\":\"attempt-1\",\"cardType\":\"TEST\",\"cardNo\":\"abcd-0000-0000-0000\"}"
        })
        void returnsBadRequest_whenBodyIsInvalid(String body) {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 1);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = confirm(idOf(user), orderId, body);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_INPUT");
            assertNothingStarted(orderId);
            assertThat(stockOf(shirt)).isEqualTo(5);
        }

        @DisplayName("시도 키가 64자를 넘으면 400 INVALID_INPUT 이고, 64자까지는 허용한다.")
        @Test
        void validatesAttemptKeyLength() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 1);
            String tooLong = "{\"paymentAttemptKey\":\"" + "a".repeat(65)
                + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}";
            String longest = "{\"paymentAttemptKey\":\"" + "a".repeat(64)
                + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}";

            // act & assert
            assertThat(confirm(idOf(user), orderId, tooLong).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertNothingStarted(orderId);
            assertThat(confirm(idOf(user), orderId, longest).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
    }

    @DisplayName("결제 결과를 확인하면,")
    @Nested
    class Reconcile {
        @DisplayName("PG 가 아직 처리 중이면 202 다. 주문은 PAYMENT_PENDING 으로 남고 lookupUnavailable 은 응답에 없다.")
        @Test
        void returnsAccepted_whilePgIsPending() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            confirm(idOf(user), orderId, VALID_BODY);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = reconcile(idOf(user), orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().data().orderStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(response.getBody().data().payment().status()).isEqualTo("PENDING");
            assertThat(response.getBody().data().lookupUnavailable()).isNull();
        }

        @DisplayName("PG 가 SUCCESS 라고 확인되면 200 이고 CONFIRMED 다. 주문 상세에 결제액이 보이고 재고는 추가로 차감되지 않는다.")
        @Test
        void returnsOk_andConfirms_whenPgSucceeded() {
            // arrange
            User user = saveUser(10_000);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            confirm(idOf(user), orderId, VALID_BODY);
            pg.setStatus(pgKeyOf(orderId), "SUCCESS");

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = reconcile(idOf(user), orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().orderStatus()).isEqualTo("CONFIRMED");
            assertThat(response.getBody().data().payment()).isEqualTo(new OrderDto.AttemptResponse("SUCCEEDED", 7_000L));
            OrderDto.OrderResponse detail = getDetail(idOf(user), orderId).getBody().data();
            assertThat(detail.status()).isEqualTo("CONFIRMED");
            assertThat(detail.payment()).isEqualTo(new OrderDto.PaymentResponse(7_000L));
            assertThat(stockOf(shirt)).isEqualTo(3);
            assertThat(balanceOf(user)).isEqualTo(10_000L);
        }

        @DisplayName("PG 가 FAILED 라고 확인되면 200 이고 PAYMENT_FAILED 다. 재고는 돌아오고, 다시 확인해도 같은 응답이다.")
        @Test
        void returnsOk_andReturnsStock_whenPgFailed() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            confirm(idOf(user), orderId, VALID_BODY);
            pg.setStatus(pgKeyOf(orderId), "FAILED");

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> first = reconcile(idOf(user), orderId);
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> second = reconcile(idOf(user), orderId);

            // assert
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(first.getBody().data().orderStatus()).isEqualTo("PAYMENT_FAILED");
            assertThat(second.getBody().data()).isEqualTo(first.getBody().data());
            assertThat(stockOf(shirt)).isEqualTo(5);
        }

        @DisplayName("PG 를 조회하지 못하면 202 와 함께 lookupUnavailable 을 알린다. 실패로 확정하거나 재고를 돌려주지 않는다.")
        @Test
        void returnsAcceptedWithLookupUnavailable_whenPgCannotBeQueried() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 2);
            confirm(idOf(user), orderId, VALID_BODY);
            pg.failLookups(500);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> response = reconcile(idOf(user), orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().data().lookupUnavailable()).isTrue();
            assertThat(response.getBody().data().orderStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(stockOf(shirt)).isEqualTo(3);
        }

        @DisplayName("다른 사용자의 주문은 404 NOT_FOUND 이고, 결제를 시작하지 않은 주문은 409 CONFLICT 다. 둘 다 PG 를 조회하지 않는다.")
        @Test
        void rejects_whenNotOwnerOrNoAttempt() {
            // arrange
            User owner = saveUser(0);
            User other = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(owner, shirt, 1);

            // act
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> notOwner = reconcile(idOf(other), orderId);
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> noAttempt = reconcile(idOf(owner), orderId);

            // assert
            assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(noAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(noAttempt.getBody().meta().errorCode()).isEqualTo("CONFLICT");
            assertThat(pg.lookupByKeyCalls() + pg.lookupByOrderCalls()).isZero();
        }

        @DisplayName("orderId 가 숫자가 아니면 400, X-USER-ID 헤더가 없으면 400 INVALID_INPUT 이다 (식별 경로 등록 확인).")
        @Test
        void returnsBadRequest_whenOrderIdIsNotNumberOrHeaderIsMissing() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_500, 5);
            Long orderId = placeOrder(user, shirt, 1);

            // act & assert
            assertThat(reconcile(idOf(user), "abc").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> noHeader = reconcile(null, orderId);
            assertThat(noHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(noHeader.getBody().meta().errorCode()).isEqualTo("INVALID_INPUT");
        }
    }
}
