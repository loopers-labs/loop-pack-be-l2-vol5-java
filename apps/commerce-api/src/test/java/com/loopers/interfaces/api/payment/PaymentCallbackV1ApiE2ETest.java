package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.application.payment.PaymentRequestFacade;
import com.loopers.application.payment.PaymentResultService;
import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

// PG 결과 알림 수신을 실제 HTTP와 실제 adapter·로컬 테스트 PG로 확인한다.
// 알림은 테스트가 직접 보내고, 결과는 PG 조회로만 정해지는지 본다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentCallbackV1ApiE2ETest {

    private static final String CALLBACK = "/api/v1/payments/callback";
    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @Autowired
    private PaymentResultService paymentResultService;

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
    class Processed {

        @DisplayName("PG 조회로 성공을 확인하면 200이고 주문을 확정하며 재고는 차감 상태를 유지한다.")
        @Test
        void appliesResultConfirmedByLookup() throws Exception {
            Fixture fixture = requested(null);
            TestPgControl.complete(fixture.providerOrderId());

            ResponseEntity<String> response = callback(fixture, "SUCCESS");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
        }

        @DisplayName("알림 본문의 성공 문자열만으로는 확정하지 않는다. PG 거래가 PENDING이면 결제 대기를 유지한다.")
        @Test
        void ignoresStatusInBody() {
            Fixture fixture = requested(null);

            ResponseEntity<String> response = callback(fixture, "SUCCESS");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        }

        @DisplayName("거래 키·상태가 없는 알림도 PG 주문 식별자로 시도를 찾아 처리한다.")
        @Test
        void acceptsNotificationWithoutTransactionKeyAndStatus() {
            Fixture fixture = requested(null);
            TestPgControl.complete(fixture.providerOrderId());

            ResponseEntity<String> response = post("{\"providerOrderId\":\"" + fixture.providerOrderId() + "\"}");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("같은 실패 알림을 두 번 받아도 재고는 한 번만 반환하고, 종결된 뒤에는 PG를 다시 조회하지 않는다.")
        @Test
        void handlesDuplicateNotificationOnce() {
            Fixture fixture = requested("FAIL");
            TestPgControl.complete(fixture.providerOrderId());

            ResponseEntity<String> first = callback(fixture, "FAILED");
            int lookups = TestPgControl.stats(fixture.providerOrderId()).lookupRequests();
            ResponseEntity<String> duplicate = callback(fixture, "FAILED");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).lookupRequests()).isEqualTo(lookups);
        }

        @DisplayName("알림이 먼저 오든 조회가 먼저 반영하든 같은 거래는 같은 최종 상태로 수렴한다.")
        @Test
        void convergesRegardlessOfOrder() throws Exception {
            Fixture callbackFirst = requested(null);
            String callbackFirstKey = pgTransactionKey(callbackFirst);
            TestPgControl.complete(callbackFirst.providerOrderId());
            callback(callbackFirst, "SUCCESS");
            paymentResultService.check(callbackFirst.attemptId());

            suffix = UUID.randomUUID().toString().substring(0, 8);
            Fixture lookupFirst = requested(null);
            String lookupFirstKey = pgTransactionKey(lookupFirst);
            TestPgControl.complete(lookupFirst.providerOrderId());
            paymentResultService.check(lookupFirst.attemptId());
            ResponseEntity<String> late = callback(lookupFirst, "SUCCESS");

            assertThat(late.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(attempt(callbackFirst).getTransactionKey()).isEqualTo(callbackFirstKey);
            assertThat(attempt(lookupFirst).getTransactionKey()).isEqualTo(lookupFirstKey);
            for (Fixture fixture : List.of(callbackFirst, lookupFirst)) {
                assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(order(fixture).getPaymentAmount()).isEqualTo(fixture.amount());
                assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
                assertThat(attempt(fixture).getTransactionKey()).isNotBlank();
                assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
            }
        }
    }

    @Nested
    class Rejected {

        @DisplayName("본문 형식이 잘못됐거나 PG 주문 식별자가 없거나 주문번호 규칙을 어기면 400이다.")
        @ParameterizedTest
        @ValueSource(strings = {
            "",
            "{",
            "{}",
            "{\"providerOrderId\":null}",
            "{\"providerOrderId\":123456}",
            "{\"providerOrderId\":\"abc\"}",
            "{\"providerOrderId\":\"LP ORD 0000000001\"}"
        })
        void rejectsInvalidBody(String body) {
            ResponseEntity<String> response = post(body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("PG 주문 식별자에 해당하는 시도가 없으면 404다.")
        @Test
        void rejectsUnknownAttempt() {
            ResponseEntity<String> response = post("{\"providerOrderId\":\"LP-ORD-0000000099-00000000\"}");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class Failures {

        @DisplayName("PG 조회가 불가능하면 503으로 재전송을 요청하고 상태를 바꾸지 않으며, 다시 받으면 반영한다.")
        @Test
        void asksRetryWhenLookupIsUnavailable() {
            Fixture fixture = requested(null);
            TestPgControl.complete(fixture.providerOrderId());
            AtomicInteger lookups = new AtomicInteger();
            doAnswer(invocation -> lookups.incrementAndGet() == 1
                ? new PaymentLookupObservation.Unavailable("조회 불가 주입")
                : invocation.callRealMethod()).when(paymentGateway).lookup(anyString());

            ResponseEntity<String> unavailable = callback(fixture, "SUCCESS");

            assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);

            ResponseEntity<String> retried = callback(fixture, "SUCCESS");

            assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("결과 반영이 저장 오류로 rollback되면 503이고 상태를 바꾸지 않으며, 재전송된 알림은 한 번 반영한다.")
        @Test
        void asksRetryWhenReflectionRollsBack() {
            Fixture fixture = requested("FAIL");
            TestPgControl.complete(fixture.providerOrderId());
            AtomicInteger terminations = new AtomicInteger();
            doAnswer(invocation -> {
                if (terminations.incrementAndGet() == 1) {
                    throw new DataAccessResourceFailureException("결과 반영 저장 실패 주입");
                }
                return invocation.callRealMethod();
            }).when(paymentAttemptRepository).terminate(anyLong(), any(), anyString(), any());

            ResponseEntity<String> rolledBack = callback(fixture, "FAILED");

            assertThat(rolledBack.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);

            ResponseEntity<String> retried = callback(fixture, "FAILED");

            assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK);
        }

        @DisplayName("미종결 시도의 주문이 결제 대기가 아니면 정합성 오류 500이고 시도·주문·재고를 바꾸지 않는다.")
        @Test
        void answersInternalErrorOnIntegrityError() {
            Fixture fixture = requested(null);
            TestPgControl.complete(fixture.providerOrderId());
            jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", fixture.orderId());

            ResponseEntity<String> response = callback(fixture, "SUCCESS");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(order(fixture).getPaymentAmount()).isNull();
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
        }

        @DisplayName("commit 뒤 저장 상태를 다시 읽지 못하면 500이지만 반영은 유지되고, 재전송된 알림은 추가 반환 없이 200이다.")
        @Test
        void keepsCommittedResultWhenStoredStateCannotBeRead() {
            Fixture fixture = requested("FAIL");
            TestPgControl.complete(fixture.providerOrderId());
            AtomicInteger orderReads = new AtomicInteger();
            doAnswer(invocation -> {
                if (orderReads.incrementAndGet() == 2) {
                    throw new DataAccessResourceFailureException("저장 상태 재조회 실패 주입");
                }
                return invocation.callRealMethod();
            }).when(orderRepository).findById(fixture.orderId());

            ResponseEntity<String> failed = callback(fixture, "FAILED");

            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK);

            ResponseEntity<String> retried = callback(fixture, "FAILED");

            assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK);
        }
    }

    @DisplayName("응답 본문은 기존 ApiResponse 형식이다.")
    @Test
    void answersWithApiResponseFormat() throws Exception {
        Fixture fixture = requested(null);

        JsonNode body = objectMapper.readTree(callback(fixture, "PENDING").getBody());

        assertThat(body.path("meta").path("result").asText()).isEqualTo("SUCCESS");
    }

    // 결제를 요청해 PG 거래를 만든다. 시나리오가 있으면 생성 전에 등록한다.
    private Fixture requested(String scenario) {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 3_500L);
        product.changeStockTo(INITIAL_STOCK);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(saved.getId(), saved.getName(), saved.getPrice(), QUANTITY))));
        String providerOrderId = "LP-ORD-%010d-%s".formatted(order.getId(), suffix);
        if (scenario != null) {
            TestPgControl.scenario(providerOrderId, "{\"scenario\":\"" + scenario + "\"}");
        }
        paymentRequestFacade.requestPayment(user.getId(), order.getId(), UUID.randomUUID().toString(), "TEST", "0000");
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(order.getId()).orElseThrow();
        return new Fixture(attempt.getId(), order.getId(), saved.getId(), providerOrderId, order.getTotalAmount());
    }

    private ResponseEntity<String> callback(Fixture fixture, String status) {
        String transactionKey = attempt(fixture).getTransactionKey();
        return post("{\"providerOrderId\":\"" + fixture.providerOrderId() + "\",\"transactionKey\":"
            + (transactionKey == null ? "null" : "\"" + transactionKey + "\"") + ",\"status\":\"" + status + "\"}");
    }

    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(CALLBACK, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow();
    }

    private String pgTransactionKey(Fixture fixture) throws Exception {
        JsonNode transactions = objectMapper.readTree(TestPgControl.lookupBodyByOrder(fixture.providerOrderId()));
        assertThat(transactions).hasSize(1);
        String transactionKey = transactions.get(0).path("transactionKey").asText();
        assertThat(transactionKey).isNotBlank();
        return transactionKey;
    }

    private Order order(Fixture fixture) {
        return orderRepository.findById(fixture.orderId()).orElseThrow();
    }

    private long stock(Fixture fixture) {
        return productRepository.findById(fixture.productId()).orElseThrow().getStock().amount();
    }

    private record Fixture(Long attemptId, Long orderId, Long productId, String providerOrderId, long amount) {}
}
