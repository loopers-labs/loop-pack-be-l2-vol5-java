package com.loopers.interfaces.api.payment;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentFinalResult;
import com.loopers.domain.payment.PaymentResultService;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.ConcurrentRunner;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/v1/payments/callback 과 POST /api/v1/orders/{orderId}/payment/reconcile 을 실제 HTTP 로 검증한다 (설계 5-6·7-5·7-6).
 * callback 본문의 status 는 신뢰하지 않고 가짜 PG 의 권위 조회로만 반영되는지, 조작·중복·조회 실패에 상태가 바뀌지 않는지 본다.
 * "PG 조회 0회" 는 가짜 PG 의 lookupCallCount 로 센다 (결제 시도의 lookupCount 는 스케줄러 전용).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakePaymentGatewayConfig.class)
class PaymentV1ApiE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String ENDPOINT_CALLBACK = "/api/v1/payments/callback";
    private static final String ENDPOINT_ORDERS = "/api/v1/orders";
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";

    private final TestRestTemplate testRestTemplate;
    private final OrderFacade orderFacade;
    private final PaymentFacade paymentFacade;
    private final PaymentResultService paymentResultService;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public PaymentV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        OrderFacade orderFacade,
        PaymentFacade paymentFacade,
        PaymentResultService paymentResultService,
        FakePaymentGateway fakePaymentGateway,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        OrderJpaRepository orderJpaRepository,
        PaymentAttemptJpaRepository paymentAttemptJpaRepository,
        StockReservationJpaRepository stockReservationJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.orderFacade = orderFacade;
        this.paymentFacade = paymentFacade;
        this.paymentResultService = paymentResultService;
        this.fakePaymentGateway = fakePaymentGateway;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.paymentAttemptJpaRepository = paymentAttemptJpaRepository;
        this.stockReservationJpaRepository = stockReservationJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
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

    @DisplayName("POST /api/v1/payments/callback 으로 PG 알림이 오면, ")
    @Nested
    class Callback {
        @DisplayName("본문이 SUCCESS 라도 PG 조회가 PENDING 이면, 확정하지 않고 202 와 저장 상태(PENDING)를 돌려주며 재고는 3 그대로다.")
        @Test
        void doesNotConfirm_whenBodySaysSuccessButPgSaysPending() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> response =
                callback(attempt.getProviderOrderId(), attempt.getTransactionKey(), "SUCCESS");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().data().providerOrderId()).isEqualTo(attempt.getProviderOrderId());
            assertThat(response.getBody().data().orderStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(response.getBody().data().attemptStatus()).isEqualTo("PENDING");
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
        }

        @DisplayName("PG 조회가 SUCCESS 면, 200 과 CONFIRMED·SUCCESS 를 돌려주고 예약은 CONSUMED, 재고는 3 (추가 차감 없음), create 는 1회다.")
        @Test
        void confirms_whenPgSaysSuccess() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> response =
                callback(attempt.getProviderOrderId(), attempt.getTransactionKey(), "SUCCESS");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().orderStatus()).isEqualTo("CONFIRMED");
            assertThat(response.getBody().data().attemptStatus()).isEqualTo("SUCCESS");
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(orderOf(orderId).getPaidAmount()).isEqualTo(2000L);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.CONSUMED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        @DisplayName("callback 이 먼저 종결한 뒤 reconcile 이 오면, 같은 최종 상태이고 reconcile 은 PG 를 조회하지 않는다.")
        @Test
        void convergesToSameState_whenCallbackComesFirst() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> first =
                callback(attempt.getProviderOrderId(), attempt.getTransactionKey(), "SUCCESS");
            OrderConfirmInfo reconciled = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(reconciled.settled()).isTrue();
            assertThat(reconciled.attemptStatus()).isEqualTo("SUCCESS");
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
            assertSettledSuccess(orderId, productId, attempt.getTransactionKey());
        }

        @DisplayName("reconcile 이 먼저 종결한 뒤 callback 이 오면, 같은 최종 상태이고 callback 은 200 이며 PG 를 조회하지 않는다.")
        @Test
        void convergesToSameState_whenReconcileComesFirst() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            OrderConfirmInfo reconciled = paymentFacade.reconcile(userId, orderId);
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> late =
                callback(attempt.getProviderOrderId(), attempt.getTransactionKey(), "SUCCESS");

            // assert
            assertThat(reconciled.settled()).isTrue();
            assertThat(late.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(late.getBody().data().attemptStatus()).isEqualTo("SUCCESS");
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
            assertSettledSuccess(orderId, productId, attempt.getTransactionKey());
        }

        @DisplayName("FAILED 결과에 callback 과 reconcile 이 동시에 들어오면, 둘 다 오류 없이 끝나고 예약 반환은 1회라 재고는 5 (7 아님)다.")
        @Test
        void appliesOnce_whenCallbackAndReconcileRunConcurrently() throws InterruptedException {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markFailed(attempt.getTransactionKey());

            // act
            List<ConcurrentRunner.Run<String>> runs = ConcurrentRunner.runAll(List.of(
                () -> callback(attempt.getProviderOrderId(), attempt.getTransactionKey(), "FAILED").getStatusCode().toString(),
                () -> paymentFacade.reconcile(userId, orderId).attemptStatus()
            ), TIMEOUT);

            // assert
            assertThat(runs).noneMatch(ConcurrentRunner.Run::failed);
            assertThat(runs.get(0).value()).isEqualTo(HttpStatus.OK.toString());
            assertThat(runs.get(1).value()).isEqualTo("FAILED");
            PaymentAttempt settled = attemptOf(orderId);
            assertThat(settled.getStatus()).isEqualTo(PaymentAttempt.Status.FAILED);
            assertThat(settled.getFailureReason()).isEqualTo(PaymentAttempt.FailureReason.PG_FAILED);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RELEASED);
            assertThat(stockOf(productId)).isEqualTo(5);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        @DisplayName("같은 callback 이 두 번 오면, 두 번째는 PG 조회 0회로 200 을 돌려주고 추가 효과가 없다.")
        @Test
        void hasNoEffect_whenCallbackIsDuplicated() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            assertThat(callback(attempt.getProviderOrderId(), attempt.getTransactionKey(), "SUCCESS").getStatusCode())
                .isEqualTo(HttpStatus.OK);
            int lookupsAfterFirst = fakePaymentGateway.lookupCallCount();

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> duplicate =
                callback(attempt.getProviderOrderId(), attempt.getTransactionKey(), "SUCCESS");

            // assert
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(duplicate.getBody().data().attemptStatus()).isEqualTo("SUCCESS");
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(lookupsAfterFirst);
            assertSettledSuccess(orderId, productId, attempt.getTransactionKey());
        }

        @DisplayName("조작: 시도는 있지만 PG 에 거래가 0건이면, 409 로 거절하고 충돌(NO_TRANSACTION)을 기록하며 상태·재고는 그대로다.")
        @Test
        void rejectsAndRecordsConflict_whenPgHasNoTransaction() {
            // arrange - 거래 없이 PENDING 으로 저장된 시도 (PG 에는 아무것도 없다)
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 3);
            Long orderId = saveAttemptFixture(userId, productId, 2, "k1", PaymentAttempt.Status.PENDING, "FAKE-TX-999999");
            String providerOrderId = PaymentAttempt.providerOrderIdOf(orderId);

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> response =
                callback(providerOrderId, "FAKE-TX-999999", "SUCCESS");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertUnchangedWithConflict(orderId, productId, PaymentAttempt.Status.PENDING, PaymentAttempt.ConflictReason.NO_TRANSACTION);
        }

        @DisplayName("조작: PG 거래 금액이 시도 금액과 다르면, PG 가 SUCCESS 라도 409 로 거절하고 충돌(AMOUNT_MISMATCH)을 기록하며 확정하지 않는다.")
        @Test
        void rejectsAndRecordsConflict_whenAmountDiffers() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            jdbcTemplate.update("UPDATE payment_attempts SET amount = 999 WHERE id = ?", attempt.getId());
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> response =
                callback(attempt.getProviderOrderId(), attempt.getTransactionKey(), "SUCCESS");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertUnchangedWithConflict(orderId, productId, PaymentAttempt.Status.PENDING, PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);
        }

        @DisplayName("조작: 다른 주문의 transactionKey 를 끼워 넣으면, 목록에 그 거래가 없어 409 로 거절하고 충돌(TRANSACTION_KEY_MISSING)을 기록하며 두 주문 모두 그대로다.")
        @Test
        void rejectsAndRecordsConflict_whenTransactionKeyBelongsToAnotherOrder() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderA = createOrder(userId, productId, 1);
            Long orderB = createOrder(userId, productId, 1);
            PaymentAttempt attemptA = startPayment(userId, orderA, "kA");
            PaymentAttempt attemptB = startPayment(userId, orderB, "kB");
            fakePaymentGateway.markSuccess(attemptA.getTransactionKey());
            fakePaymentGateway.markSuccess(attemptB.getTransactionKey());

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> response =
                callback(attemptA.getProviderOrderId(), attemptB.getTransactionKey(), "SUCCESS");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertUnchangedWithConflict(orderA, productId, PaymentAttempt.Status.PENDING, PaymentAttempt.ConflictReason.TRANSACTION_KEY_MISSING);
            assertThat(attemptOf(orderB).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(attemptOf(orderB).isConflicted()).isFalse();
        }

        @DisplayName("없는 providerOrderId 면, 404 이고 PG 는 조회하지 않는다.")
        @Test
        void returnsNotFound_whenProviderOrderIdIsUnknown() {
            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> response =
                callback("LP-ORD-9999999999", "FAKE-TX-000001", "SUCCESS");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(fakePaymentGateway.lookupCallCount()).isZero();
        }

        @DisplayName("PG 조회가 실패하면, 5xx 이고 시도(REQUESTED 그대로)·주문·예약·재고는 완전히 불변이며 충돌도 기록하지 않는다.")
        @Test
        void returnsServerError_andKeepsEverything_whenLookupFails() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 3);
            Long orderId = saveAttemptFixture(userId, productId, 2, "k1", PaymentAttempt.Status.REQUESTED, null);
            fakePaymentGateway.willFailLookups();

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> response =
                callback(PaymentAttempt.providerOrderIdOf(orderId), "FAKE-TX-000001", "SUCCESS");

            // assert - PG 에 실제로 1회 물어봤고, 그 실패가 5xx 로 나온다 (물어보지도 않은 5xx 와 구분)
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.REQUESTED);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(attempt.isConflicted()).isFalse();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
        }

        @DisplayName("서킷이 차단 중이면, 조회 실패와 같이 5xx 이고 시도(REQUESTED 그대로)·주문·예약·재고는 불변이며 충돌도 기록하지 않는다 (설계 5-7 결정 ㉙).")
        @Test
        void returnsServerError_andKeepsEverything_whenCircuitIsOpen() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 3);
            Long orderId = saveAttemptFixture(userId, productId, 2, "k1", PaymentAttempt.Status.REQUESTED, null);
            fakePaymentGateway.willOpenCircuit();

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> response =
                callback(PaymentAttempt.providerOrderIdOf(orderId), "FAKE-TX-000001", "SUCCESS");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.REQUESTED);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(attempt.isConflicted()).isFalse();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/payment/reconcile 을 요청하면, ")
    @Nested
    class Reconcile {
        @DisplayName("시도가 진행 중이면, 202 와 저장 상태·lookupUnavailable=false·조회 경로를 돌려준다.")
        @Test
        void returnsAccepted_whenAttemptIsInProgress() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            startPayment(userId, orderId, "k1");

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.ReconcileResponse>> response = reconcile(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
            PaymentV1Dto.ReconcileResponse data = response.getBody().data();
            assertThat(data.orderId()).isEqualTo(orderId);
            assertThat(data.orderStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(data.attemptStatus()).isEqualTo("PENDING");
            assertThat(data.lookupUnavailable()).isFalse();
            assertThat(data.links().order()).isEqualTo(ENDPOINT_ORDERS + "/" + orderId);
            assertThat(data.links().reconcile()).isEqualTo(ENDPOINT_ORDERS + "/" + orderId + "/payment/reconcile");
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
        }

        @DisplayName("시도가 종결됐으면, 200 과 저장 결과를 돌려주고 PG 는 조회하지 않는다.")
        @Test
        void returnsOkWithoutLookup_whenAttemptIsSettled() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            paymentResultService.applyFinal(attempt.getId(), PaymentFinalResult.success(attempt.getTransactionKey()));

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.ReconcileResponse>> response = reconcile(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().orderStatus()).isEqualTo("CONFIRMED");
            assertThat(response.getBody().data().attemptStatus()).isEqualTo("SUCCESS");
            assertThat(response.getBody().data().lookupUnavailable()).isFalse();
            assertThat(fakePaymentGateway.lookupCallCount()).isZero();
        }

        @DisplayName("결제 시도가 없는 주문(DRAFT, 이전 POINT 확정)이면, 404 이고 PG 는 조회하지 않는다.")
        @Test
        void returnsNotFound_whenOrderHasNoAttempt() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long draftOrderId = createOrder(userId, productId, 1);
            Long pointOrderId = createOrder(userId, productId, 1);
            jdbcTemplate.update(
                "UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', paid_amount = 1000 WHERE id = ?", pointOrderId);

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.ReconcileResponse>> draft = reconcile(userId, draftOrderId);
            ResponseEntity<ApiResponse<PaymentV1Dto.ReconcileResponse>> point = reconcile(userId, pointOrderId);

            // assert
            assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(point.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(orderOf(draftOrderId).getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(orderOf(pointOrderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(orderOf(pointOrderId).getPaymentMethod()).isEqualTo(Order.PaymentMethod.POINT);
            assertThat(fakePaymentGateway.lookupCallCount()).isZero();
        }

        @DisplayName("남의 주문이면, 기존 소유권 오류(404, 존재를 알리지 않음)이고 PG 는 조회하지 않는다.")
        @Test
        void returnsNotFound_whenRequesterIsNotOwner() {
            // arrange
            Long ownerId = saveUser();
            Long otherId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(ownerId, productId, 2);
            PaymentAttempt attempt = startPayment(ownerId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            ResponseEntity<ApiResponse<PaymentV1Dto.ReconcileResponse>> response = reconcile(otherId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().meta().message()).isEqualTo("[id = " + orderId + "] 주문을 찾을 수 없습니다.");
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(fakePaymentGateway.lookupCallCount()).isZero();
        }
    }

    private ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> callback(
        String providerOrderId, String transactionKey, String status
    ) {
        String body = "{\"providerOrderId\":\"" + providerOrderId
            + "\",\"transactionKey\":\"" + transactionKey
            + "\",\"status\":\"" + status + "\"}";
        return testRestTemplate.exchange(
            ENDPOINT_CALLBACK,
            HttpMethod.POST,
            new HttpEntity<>(body, jsonHeaders()),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<PaymentV1Dto.ReconcileResponse>> reconcile(Long userId, Long orderId) {
        HttpHeaders headers = jsonHeaders();
        headers.set(USER_ID_HEADER, String.valueOf(userId));
        return testRestTemplate.exchange(
            ENDPOINT_ORDERS + "/" + orderId + "/payment/reconcile",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {}
        );
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private void assertSettledSuccess(Long orderId, Long productId, String transactionKey) {
        PaymentAttempt attempt = attemptOf(orderId);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
        assertThat(attempt.getTransactionKey()).isEqualTo(transactionKey);
        assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.CONSUMED);
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
    }

    private void assertUnchangedWithConflict(
        Long orderId, Long productId, PaymentAttempt.Status expectedStatus, PaymentAttempt.ConflictReason expectedReason
    ) {
        PaymentAttempt attempt = attemptOf(orderId);
        assertThat(attempt.getStatus()).isEqualTo(expectedStatus);
        assertThat(attempt.isConflicted()).isTrue();
        assertThat(attempt.getConflictReason()).isEqualTo(expectedReason);
        assertThat(attempt.getConflictedAt()).isNotNull();
        assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
        assertThat(stockOf(productId)).isEqualTo(3);
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

    /**
     * confirm 으로 TX A + 가짜 PG create 를 거쳐 PENDING + transactionKey 상태의 시도를 만든다.
     */
    private PaymentAttempt startPayment(Long userId, Long orderId, String attemptKey) {
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        return attemptOf(orderId);
    }

    /**
     * PG 를 거치지 않고 "TX A 가 끝난 뒤" 상태를 직접 만든다. 상품 재고는 이미 차감된 값으로 저장해 둔다.
     * 주문 PAYMENT_PENDING·PG, 예약 RESERVED, 시도는 주어진 상태·transactionKey 다.
     */
    private Long saveAttemptFixture(
        Long userId, Long productId, int quantity, String attemptKey, PaymentAttempt.Status status, String transactionKey
    ) {
        long unitPrice = productJpaRepository.findById(productId).orElseThrow().getPrice().getAmount();
        Long orderId = orderJpaRepository.save(new Order(userId, List.of(new OrderItem(productId, quantity, unitPrice)))).getId();
        jdbcTemplate.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG' WHERE id = ?", orderId);
        stockReservationJpaRepository.save(new StockReservation(orderId, productId, quantity));
        Long attemptId = paymentAttemptJpaRepository.save(new PaymentAttempt(
            orderId, userId, attemptKey, PaymentAttempt.providerOrderIdOf(orderId), unitPrice * quantity, CARD_TYPE)).getId();
        jdbcTemplate.update("UPDATE payment_attempts SET status = ?, transaction_key = ? WHERE id = ?",
            status.name(), transactionKey, attemptId);
        return orderId;
    }

    private Order orderOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private PaymentAttempt attemptOf(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private List<StockReservation.Status> reservationStatusesOf(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId).stream().map(StockReservation::getStatus).toList();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }
}
