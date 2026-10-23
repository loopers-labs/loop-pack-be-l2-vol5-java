package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.application.payment.PaymentRequestFacade;
import com.loopers.application.payment.PaymentRequestTransaction;
import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// 고객의 결제 결과 재확인(reconcile)을 실제 HTTP와 실제 adapter·로컬 테스트 PG로 확인한다.
// 테스트 PG가 보내는 callback은 이 서버에 닿지 않으므로 웹훅이 누락된 상태와 같다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderPaymentReconcileV1ApiE2ETest {

    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @Autowired
    private PaymentRequestTransaction paymentRequestTransaction;

    @MockitoBean
    private ProviderOrderSuffixGenerator suffixGenerator;

    @MockitoSpyBean
    private PaymentGateway paymentGateway;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenAnswer(invocation -> suffix);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    class Reflects {

        @DisplayName("웹훅이 누락된 PG 성공을 재확인하면 200으로 주문을 확정하고, 생성·재고 차감을 추가하지 않는다.")
        @Test
        void confirmsSuccessMissedByWebhook() throws Exception {
            Fixture fixture = requested(null);
            TestPgControl.complete(fixture.providerOrderId());

            ResponseEntity<String> response = reconcile(fixture.userId(), fixture.orderId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode data = data(response);
            assertThat(data.path("orderStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(data.path("paymentProgress").asText()).isEqualTo("COMPLETED");
            assertThat(data.path("attemptStatus").asText()).isEqualTo("SUCCEEDED");
            assertThat(data.path("paymentResult").asText()).isEqualTo("SUCCESS");
            assertThat(data.path("paymentAmount").asLong()).isEqualTo(fixture.amount());
            assertThat(data.path("links").has("reconcile")).isFalse();
            assertThat(TestPgControl.stats(fixture.providerOrderId()).createRequests()).isEqualTo(1);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).transactions()).isEqualTo(1);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
        }

        @DisplayName("PG가 아직 처리 중이면 202와 결제 대기 상태·재확인 경로를 응답하고 상태를 바꾸지 않는다.")
        @Test
        void keepsWaitingWhilePgIsPending() throws Exception {
            Fixture fixture = requested(null);

            ResponseEntity<String> response = reconcile(fixture.userId(), fixture.orderId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode data = data(response);
            assertThat(data.path("orderStatus").asText()).isEqualTo("PAYMENT_PENDING");
            assertThat(data.path("paymentProgress").asText()).isEqualTo("WAITING");
            assertThat(data.path("lookupUnavailable").asText()).isEqualTo("false");
            assertThat(data.path("links").path("reconcile").asText())
                .isEqualTo("/api/v1/orders/" + fixture.orderId() + "/payment/reconcile");
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
        }

        @DisplayName("PG 실패를 재확인하면 200으로 실패 종결하고 재고를 반환하며, 다시 재확인해도 PG 조회·반환을 반복하지 않는다.")
        @Test
        void failsOnceAndAnswersStoredResultAfterward() throws Exception {
            Fixture fixture = requested("FAIL");
            TestPgControl.complete(fixture.providerOrderId());

            ResponseEntity<String> first = reconcile(fixture.userId(), fixture.orderId());
            int lookups = TestPgControl.stats(fixture.providerOrderId()).lookupRequests();
            ResponseEntity<String> again = reconcile(fixture.userId(), fixture.orderId());

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(data(again).path("lookupUnavailable").asText()).isEqualTo("false");
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).lookupRequests()).isEqualTo(lookups);
        }

        @DisplayName("생성 응답을 잃은 시도는 PG 주문 식별자로 기존 거래를 찾아 종결하고 생성을 다시 보내지 않는다.")
        @Test
        void recoversDroppedCreateResponse() {
            Fixture fixture = requested("DROP_RESPONSE");
            assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            TestPgControl.complete(fixture.providerOrderId());

            ResponseEntity<String> response = reconcile(fixture.userId(), fixture.orderId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            TestPgControl.Stats stats = TestPgControl.stats(fixture.providerOrderId());
            assertThat(stats.createRequests()).isEqualTo(1);
            assertThat(stats.transactions()).isEqualTo(1);
        }

        @DisplayName("발송 전에 멈춰 PG 거래가 없는 시도는 202로 결과 미확인을 유지하고 재고를 반환하지 않으며 생성을 보내지 않는다.")
        @Test
        void keepsUnconfirmedWithoutTransaction() throws Exception {
            Fixture fixture = draft();
            paymentRequestTransaction.reserve(fixture.userId(), fixture.orderId(), UUID.randomUUID().toString());

            ResponseEntity<String> response = reconcile(fixture.userId(), fixture.orderId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(data(response).path("paymentProgress").asText()).isEqualTo("UNCONFIRMED");
            assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).createRequests()).isZero();
        }
    }

    @Nested
    class StoredStateOnFailure {

        @DisplayName("PG 조회가 실패하거나 차단되면 202와 조회 불가를 응답하고 저장 상태와 예약을 유지한다.")
        @ParameterizedTest
        @EnumSource(value = PaymentLookupObservation.FailureKind.class,
            names = {"TIMEOUT", "CIRCUIT_OPEN"})
        void answersStoredStateWhenLookupIsUnavailable(PaymentLookupObservation.FailureKind kind) throws Exception {
            Fixture fixture = requested(null);
            TestPgControl.complete(fixture.providerOrderId());
            doAnswer(invocation -> new PaymentLookupObservation.Unavailable("조회 불가 주입", kind))
                .when(paymentGateway).lookup(anyString());

            ResponseEntity<String> response = reconcile(fixture.userId(), fixture.orderId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(data(response).path("attemptStatus").asText()).isEqualTo("PENDING");
            assertThat(data(response).path("lookupUnavailable").asText()).isEqualTo("true");
            assertThat(data(response).path("paymentProgress").asText()).isEqualTo("WAITING");
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).createRequests()).isEqualTo(1);
        }

        @DisplayName("결과 반영이 저장 오류로 rollback되면 202와 저장된 상태로 응답하고, 다음 재확인에서 한 번 반영한다.")
        @Test
        void answersStoredStateWhenReflectionRollsBack() throws Exception {
            Fixture fixture = requested("FAIL");
            TestPgControl.complete(fixture.providerOrderId());
            AtomicInteger terminations = new AtomicInteger();
            doAnswer(invocation -> {
                if (terminations.incrementAndGet() == 1) {
                    throw new DataAccessResourceFailureException("결과 반영 저장 실패 주입");
                }
                return invocation.callRealMethod();
            }).when(paymentAttemptRepository).terminate(anyLong(), any(), anyString(), any());

            ResponseEntity<String> rolledBack = reconcile(fixture.userId(), fixture.orderId());

            assertThat(rolledBack.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(data(rolledBack).path("lookupUnavailable").asText()).isEqualTo("false");
            assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);

            ResponseEntity<String> retried = reconcile(fixture.userId(), fixture.orderId());

            assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK);
        }

        @DisplayName("미종결 시도의 주문이 결제 대기가 아니면 정합성 오류 500이고 시도·주문·재고를 바꾸지 않는다.")
        @Test
        void answersInternalErrorOnIntegrityError() {
            Fixture fixture = requested(null);
            TestPgControl.complete(fixture.providerOrderId());
            jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", fixture.orderId());

            ResponseEntity<String> response = reconcile(fixture.userId(), fixture.orderId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
        }

        @DisplayName("commit 뒤 저장 상태를 다시 읽지 못하면 500이지만 반영은 유지되고, 다시 재확인하면 추가 반환 없이 200이다.")
        @Test
        void keepsCommittedResultWhenStoredStateCannotBeRead() {
            Fixture fixture = requested("FAIL");
            TestPgControl.complete(fixture.providerOrderId());
            AtomicInteger orderReads = new AtomicInteger();
            // 주문 조회 순서: 소유권 확인 → TX B → 응답용 재조회. 세 번째 조회만 실패시킨다.
            doAnswer(invocation -> {
                if (orderReads.incrementAndGet() == 3) {
                    throw new DataAccessResourceFailureException("저장 상태 재조회 실패 주입");
                }
                return invocation.callRealMethod();
            }).when(orderRepository).findById(fixture.orderId());

            ResponseEntity<String> failed = reconcile(fixture.userId(), fixture.orderId());

            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK);

            ResponseEntity<String> again = reconcile(fixture.userId(), fixture.orderId());

            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK);
        }
    }

    @Nested
    class Rejected {

        @DisplayName("타인 주문·없는 주문·시도가 없는 DRAFT·이전 포인트 주문은 404이고 PG를 조회하지 않는다.")
        @Test
        void rejectsOrdersWithoutOwnAttempt() {
            Fixture fixture = requested(null);
            User other = userRepository.save(User.create());
            Fixture draft = draft();
            Order pointOrder = orderRepository.save(Order.reconstitute(null, fixture.userId(), OrderStatus.CONFIRMED,
                7_000L, 7_000L, PaymentResult.SUCCESS, PaymentMethod.POINT,
                List.of(OrderItem.create(fixture.productId(), "Air Max", 3_500L, QUANTITY))));
            int lookups = TestPgControl.stats(fixture.providerOrderId()).lookupRequests();

            assertThat(reconcile(other.getId(), fixture.orderId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(reconcile(fixture.userId(), Long.MAX_VALUE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(reconcile(draft.userId(), draft.orderId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(reconcile(fixture.userId(), pointOrder.getId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).lookupRequests()).isEqualTo(lookups);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("요청자 식별값이 없거나 주문 ID가 숫자가 아니면 400이다.")
        @Test
        void rejectsInvalidInput() {
            Fixture fixture = requested(null);

            ResponseEntity<String> missingUser = rest.exchange(
                "/api/v1/orders/" + fixture.orderId() + "/payment/reconcile", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), String.class);
            ResponseEntity<String> invalidOrderId = rest.exchange(
                "/api/v1/orders/abc/payment/reconcile", HttpMethod.POST,
                new HttpEntity<>(headers(fixture.userId())), String.class);

            assertThat(missingUser.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(invalidOrderId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private Fixture requested(String scenario) {
        Fixture fixture = draft();
        if (scenario != null) {
            TestPgControl.scenario(fixture.providerOrderId(), "{\"scenario\":\"" + scenario + "\"}");
        }
        paymentRequestFacade.requestPayment(fixture.userId(), fixture.orderId(), UUID.randomUUID().toString(), "TEST", "0000");
        return fixture;
    }

    private Fixture draft() {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 3_500L);
        product.changeStockTo(INITIAL_STOCK);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(saved.getId(), saved.getName(), saved.getPrice(), QUANTITY))));
        return new Fixture(user.getId(), order.getId(), saved.getId(),
            "LP-ORD-%010d-%s".formatted(order.getId(), suffix), order.getTotalAmount());
    }

    private ResponseEntity<String> reconcile(Long userId, Long orderId) {
        return rest.exchange("/api/v1/orders/" + orderId + "/payment/reconcile", HttpMethod.POST,
            new HttpEntity<>(headers(userId)), String.class);
    }

    private HttpHeaders headers(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId.toString());
        return headers;
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private PaymentAttemptStatus attemptStatus(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getStatus();
    }

    private Order order(Fixture fixture) {
        return orderRepository.findById(fixture.orderId()).orElseThrow();
    }

    private long stock(Fixture fixture) {
        return productRepository.findById(fixture.productId()).orElseThrow().getStock().amount();
    }

    private record Fixture(Long userId, Long orderId, Long productId, String providerOrderId, long amount) {}
}
