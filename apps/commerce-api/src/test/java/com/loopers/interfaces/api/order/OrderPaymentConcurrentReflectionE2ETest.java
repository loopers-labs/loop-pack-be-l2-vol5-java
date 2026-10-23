package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandLockTestSupport;
import com.loopers.application.payment.PaymentRequestFacade;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.loopers.application.brand.BrandLockTestSupport.TIMEOUT_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// 같은 시도의 결과를 reconcile·callback이 동시에 반영하는 경합을 실제 HTTP·MySQL·테스트 PG로 재현한다.
// 첫 요청을 시도 UPDATE 직후(행 잠금 보유)에 멈추고, 두 번째 요청이 그 행에서 실제로 기다리는지 확인한 뒤 풀어 준다.
// 두 번째 요청의 TX B 스냅샷은 아직 미종결이라 도메인 검사를 통과하지만, DB 조건부 갱신은 0행이 되어야 한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderPaymentConcurrentReflectionE2ETest {

    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @MockitoBean
    private ProviderOrderSuffixGenerator suffixGenerator;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private BrandLockTestSupport locks;

    @BeforeEach
    void setUp() {
        locks = new BrandLockTestSupport(dataSource, jdbcTemplate, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("같은 거래 결과를 두 요청이 겹쳐 반영해도 종결·재고 반환은 한 번이고, 진 요청도 저장된 결과로 200을 응답한다.")
    @ParameterizedTest(name = "{0} + {1}, PG 결과 {2}")
    @CsvSource({
        "RECONCILE, RECONCILE, FAIL",
        "CALLBACK, RECONCILE, FAIL",
        "RECONCILE, RECONCILE, SUCCESS",
        "CALLBACK, RECONCILE, SUCCESS"
    })
    void reflectsOnceWhenTwoRequestsOverlap(Trigger firstTrigger, Trigger secondTrigger, String pgResult) throws Exception {
        Fixture fixture = requested(pgResult);
        JsonNode transactions = objectMapper.readTree(TestPgControl.lookupBodyByOrder(fixture.providerOrderId()));
        assertThat(transactions).hasSize(1);
        String transactionKey = transactions.get(0).path("transactionKey").asText();
        assertThat(transactionKey).isNotBlank();
        TestPgControl.complete(fixture.providerOrderId());

        AtomicInteger terminations = new AtomicInteger();
        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong secondConnection = new AtomicLong();
        CountDownLatch secondStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (terminations.incrementAndGet() == 1) {
                Object rows = invocation.callRealMethod();
                firstUpdated.countDown();
                locks.awaitLatch(releaseFirst);
                return rows;
            }
            locks.recordConnection(secondConnection, secondStarted);
            return invocation.callRealMethod();
        }).when(paymentAttemptRepository).terminate(anyLong(), any(), anyString(), any());

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = workers.submit(() -> send(firstTrigger, fixture));
            assertThat(firstUpdated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<String>> second = workers.submit(() -> send(secondTrigger, fixture));
            assertThat(secondStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(secondConnection.get(), "payment_attempts");
            releaseFirst.countDown();

            List<ResponseEntity<String>> responses = List.of(
                first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            boolean failed = pgResult.equals("FAIL");
            OrderStatus expectedOrder = failed ? OrderStatus.PAYMENT_FAILED : OrderStatus.CONFIRMED;
            assertThat(terminations).as("두 요청 모두 시도 UPDATE까지 도달").hasValue(2);
            assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));
            for (int index = 0; index < responses.size(); index++) {
                if (List.of(firstTrigger, secondTrigger).get(index) == Trigger.RECONCILE) {
                    assertThat(objectMapper.readTree(responses.get(index).getBody()).path("data").path("orderStatus").asText())
                        .isEqualTo(expectedOrder.name());
                }
            }
            PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
            assertThat(attempt.getStatus()).isEqualTo(failed ? PaymentAttemptStatus.FAILED : PaymentAttemptStatus.SUCCEEDED);
            assertThat(attempt.getConflictReason()).isNull();
            assertThat(attempt.getTransactionKey()).isEqualTo(transactionKey);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).createRequests()).isEqualTo(1);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).transactions()).isEqualTo(1);
            Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(expectedOrder);
            assertThat(order.getPaymentAmount()).isEqualTo(failed ? null : fixture.amount());
            assertThat(productRepository.findById(fixture.productId()).orElseThrow().getStock().amount())
                .isEqualTo(failed ? INITIAL_STOCK : INITIAL_STOCK - QUANTITY);
        } finally {
            releaseFirst.countDown();
            locks.shutDown(workers);
        }
    }

    enum Trigger {
        RECONCILE,
        CALLBACK
    }

    private ResponseEntity<String> send(Trigger trigger, Fixture fixture) {
        HttpHeaders headers = new HttpHeaders();
        if (trigger == Trigger.RECONCILE) {
            headers.set("X-USER-ID", fixture.userId().toString());
            return rest.exchange("/api/v1/orders/" + fixture.orderId() + "/payment/reconcile", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/payments/callback", HttpMethod.POST,
            new HttpEntity<>("{\"providerOrderId\":\"" + fixture.providerOrderId() + "\"}", headers), String.class);
    }

    private Fixture requested(String pgResult) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenReturn(suffix);
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 3_500L);
        product.changeStockTo(INITIAL_STOCK);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(saved.getId(), saved.getName(), saved.getPrice(), QUANTITY))));
        String providerOrderId = "LP-ORD-%010d-%s".formatted(order.getId(), suffix);
        TestPgControl.scenario(providerOrderId, "{\"scenario\":\"" + pgResult + "\"}");
        paymentRequestFacade.requestPayment(user.getId(), order.getId(), UUID.randomUUID().toString(), "TEST", "0000");
        return new Fixture(user.getId(), order.getId(), saved.getId(), providerOrderId, order.getTotalAmount());
    }

    private record Fixture(Long userId, Long orderId, Long productId, String providerOrderId, long amount) {}
}
