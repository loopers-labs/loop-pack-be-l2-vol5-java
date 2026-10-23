package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentAttempt;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import com.loopers.application.payment.PaymentRequestFacade;
import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.testpg.TestPgContainer;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

// 로컬 테스트 PG 컨테이너가 실제로 보내는 결과 알림을 테스트 서버가 받아 반영하는지 확인한다.
// callback URL은 PG 컨테이너에서 호스트로 접근하는 주소와 이 테스트의 고정 포트로 정한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class PaymentCallbackDeliveryE2ETest {

    private static final int PORT = freePort();
    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;

    @DynamicPropertySource
    static void callbackAddress(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        // 서버 포트를 고정하면 관리 포트(8081)도 그대로 열리므로, 다른 컨텍스트와 겹치지 않게 무작위로 둔다.
        registry.add("management.server.port", () -> 0);
        registry.add("payment.pg.callback-url",
            () -> "http://host.testcontainers.internal:" + PORT + "/api/v1/payments/callback");
    }

    @BeforeAll
    static void exposeServerToPg() {
        TestPgContainer.baseUrl();
        Testcontainers.exposeHostPorts(PORT);
    }

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @MockitoBean
    private ProviderOrderSuffixGenerator suffixGenerator;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @MockitoSpyBean
    private PaymentGateway paymentGateway;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

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

    @DisplayName("PG가 처리 뒤 보낸 결과 알림을 받아 PG 조회로 확인하고 주문을 확정한다.")
    @Test
    void confirmsOrderFromDeliveredCallback() throws Exception {
        Fixture fixture = requested(null);
        String transactionKey = pgTransactionKey(fixture);

        TestPgControl.complete(fixture.providerOrderId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.CONFIRMED));
        assertThat(paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).callbackAttempts()).isEqualTo(1);
        assertThat(attempt(fixture).getTransactionKey()).isEqualTo(transactionKey);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).transactions()).isEqualTo(1);
        assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
    }

    @DisplayName("PG가 같은 성공 알림을 두 번 보내도 확정은 한 번이고 재고는 다시 바뀌지 않는다.")
    @Test
    void confirmsOnceForDuplicateDeliveredCallbacks() throws Exception {
        Fixture fixture = requested("{\"scenario\":\"DUPLICATE_CALLBACK\"}");
        String transactionKey = pgTransactionKey(fixture);

        TestPgControl.complete(fixture.providerOrderId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(TestPgControl.stats(fixture.providerOrderId()).callbackAttempts()).isEqualTo(2));
        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(attempt(fixture).getTransactionKey()).isEqualTo(transactionKey);
        assertThat(stock(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY);
    }

    private Fixture requested(String scenarioJson) {
        Fixture fixture = draft(scenarioJson);
        paymentRequestFacade.requestPayment(fixture.userId(), fixture.orderId(), UUID.randomUUID().toString(), "TEST", "0000");
        return fixture;
    }

    private Fixture draft(String scenarioJson) {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 3_500L);
        product.changeStockTo(INITIAL_STOCK);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(saved.getId(), saved.getName(), saved.getPrice(), QUANTITY))));
        String providerOrderId = "LP-ORD-%010d-%s".formatted(order.getId(), suffix);
        if (scenarioJson != null) {
            TestPgControl.scenario(providerOrderId, scenarioJson);
        }
        return new Fixture(user.getId(), order.getId(), saved.getId(), providerOrderId);
    }

    @DisplayName("PG callback이 먼저 종결한 뒤 늦게 도착한 생성 응답(접수·timeout)은 상태를 되돌리지 않고, confirm은 종결 상태로 200을 응답한다.")
    @ParameterizedTest(name = "늦은 생성 응답 {0}, PG 결과 {1}")
    @CsvSource({
        "ACCEPTED, SUCCESS",
        "ACCEPTED, FAIL",
        "TIMEOUT, SUCCESS"
    })
    void keepsTerminalResultWhenCreateResponseArrivesAfterCallback(String lateResponse, String pgResult) throws Exception {
        // 시나리오는 주문당 하나라 응답 유실(DROP_RESPONSE)은 성공 결과로만 만든다.
        String scenario = lateResponse.equals("TIMEOUT") ? "DROP_RESPONSE" : pgResult;
        Fixture fixture = draft("{\"scenario\":\"" + scenario + "\"}");
        AtomicReference<PaymentAttemptStatus> statusBeforeLateResponse = new AtomicReference<>();
        // 실제 생성 요청이 끝난 뒤, 그 관찰을 confirm에 돌려주기 전에 PG를 처리해 callback이 먼저 종결하게 한다.
        doAnswer(invocation -> {
            PaymentCreateObservation observation = (PaymentCreateObservation) invocation.callRealMethod();
            TestPgControl.complete(fixture.providerOrderId());
            await().atMost(Duration.ofSeconds(10)).until(() -> attempt(fixture).isTerminal());
            statusBeforeLateResponse.set(attempt(fixture).getStatus());
            return observation;
        }).when(paymentGateway).create(any());

        ResponseEntity<String> response = confirm(fixture);

        boolean failed = pgResult.equals("FAIL");
        PaymentAttemptStatus terminal = failed ? PaymentAttemptStatus.FAILED : PaymentAttemptStatus.SUCCEEDED;
        assertThat(statusBeforeLateResponse.get()).as("늦은 생성 응답 전에 callback이 종결").isEqualTo(terminal);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("orderStatus").asText()).isEqualTo(failed ? "PAYMENT_FAILED" : "CONFIRMED");
        assertThat(data.path("attemptStatus").asText()).isEqualTo(terminal.name());
        PaymentAttempt attempt = attempt(fixture);
        assertThat(attempt.getStatus()).isEqualTo(terminal);
        assertThat(attempt.getTransactionKey()).isEqualTo(pgTransactionKey(fixture));
        assertThat(attempt.getConflictReason()).isNull();
        assertThat(stock(fixture)).isEqualTo(failed ? INITIAL_STOCK : INITIAL_STOCK - QUANTITY);
        TestPgControl.Stats stats = TestPgControl.stats(fixture.providerOrderId());
        assertThat(stats.createRequests()).isEqualTo(1);
        assertThat(stats.callbackAttempts()).isEqualTo(1);
    }

    private ResponseEntity<String> confirm(Fixture fixture) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", fixture.userId().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"paymentAttemptKey\":\"" + UUID.randomUUID() + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000\"}";
        return rest.exchange("/api/v1/orders/" + fixture.orderId() + "/confirm", HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class);
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
    }

    private String pgTransactionKey(Fixture fixture) throws Exception {
        JsonNode transactions = objectMapper.readTree(TestPgControl.lookupBodyByOrder(fixture.providerOrderId()));
        assertThat(transactions).hasSize(1);
        return transactions.get(0).path("transactionKey").asText();
    }

    private OrderStatus orderStatus(Fixture fixture) {
        return orderRepository.findById(fixture.orderId()).orElseThrow().getStatus();
    }

    private long stock(Fixture fixture) {
        return productRepository.findById(fixture.productId()).orElseThrow().getStock().amount();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Fixture(Long userId, Long orderId, Long productId, String providerOrderId) {}
}
