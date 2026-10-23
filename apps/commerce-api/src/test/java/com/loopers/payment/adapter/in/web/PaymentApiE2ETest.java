package com.loopers.payment.adapter.in.web;

import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.point.adapter.out.persistence.PointHistoryJpaRepository;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제 API (W5 plan.md 8). 로컬 테스트 PG가 실제 HTTP로 callback을 보내야 하므로 서버를 정해 둔 빈 포트로 띄운다.
 * 클라이언트 요청은 MockMvc로, PG callback은 fixture가 그 포트로 보낸다. 풀 거절을 재현하려고 발송 풀을 1개로 줄인다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
        "pg.local.create-read-timeout=500ms",
        "pg.local.lookup-read-timeout=300ms",
        "payment.dispatch.pool-size=1",
    })
@AutoConfigureMockMvc
class PaymentApiE2ETest {

    private static final LocalTestPgServer PG = LocalTestPgServer.start();
    private static final int PORT = freePort();
    private static final String USER_HEADER = "X-USER-ID";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String CARD_NO = "1234-5678-9814-1451";
    private static final String CARD_BODY = "{\"cardType\": \"SAMSUNG\", \"cardNo\": \"" + CARD_NO + "\"}";
    private static final Duration WAIT = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("pg.local.base-url", PG::baseUrl);
        registry.add("pg.local.callback-url", () -> "http://127.0.0.1:" + PORT + "/api/v1/payments/callback");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private UserModel other;
    private ProductModel airMax;

    @BeforeEach
    void setUp() {
        PG.reset();
        circuitBreakerRegistry.circuitBreaker("pgCreate").reset();
        circuitBreakerRegistry.circuitBreaker("pgLookup").reset();
        user = userJpaRepository.save(new UserModel("고객"));
        other = userJpaRepository.save(new UserModel("다른 고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 재고 5에서 2개(2,000원)를 주문한다. */
    private Long createOrder() throws Exception {
        String response = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"productId\": " + airMax.getId() + ", \"quantity\": 2}]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(response).read("$.data.id", Long.class);
    }

    private MockHttpServletRequestBuilder confirm(Long orderId, UserModel requester, String key) {
        MockHttpServletRequestBuilder request = post("/api/v1/orders/{orderId}/confirm", orderId)
            .header(USER_HEADER, requester.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(CARD_BODY);
        return key == null ? request : request.header(KEY_HEADER, key);
    }

    private Payment paymentOf(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private Long pendingPayment(String key) throws Exception {
        Long orderId = createOrder();
        mockMvc.perform(confirm(orderId, user, key)).andExpect(status().isAccepted());
        await().atMost(WAIT).until(() -> paymentOf(orderId).getStatus() == PaymentStatus.PENDING);
        return orderId;
    }

    private int stock() {
        return productJpaRepository.findById(airMax.getId()).orElseThrow().getStock();
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {

        @DisplayName("S-1 결제를 접수하면 202이고 주문 PAYMENT_PENDING·결제 상태를 돌려주며, 카드 번호는 응답에 없다. 포인트는 쓰지 않는다.")
        @Test
        void acceptsWith202() throws Exception {
            // arrange
            Long orderId = createOrder();

            // act & assert
            mockMvc.perform(confirm(orderId, user, "attempt-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentStatus").value(anyOf(is("REQUESTED"), is("SENDING"), is("PENDING"))))
                .andExpect(content().string(not(containsString(CARD_NO))));
            await().atMost(WAIT).until(() -> paymentOf(orderId).getStatus() == PaymentStatus.PENDING);
            assertThat(stock()).isEqualTo(3);
            assertThat(pointHistoryJpaRepository.count()).isZero();
        }

        @DisplayName("S-5 같은 Idempotency-Key로 다시 보내면 같은 시도 상태를 돌려주고, PG 생성은 1번이다.")
        @Test
        void sameKeyReturnsSameAttempt() throws Exception {
            // arrange
            Long orderId = pendingPayment("attempt-1");
            String transactionKey = paymentOf(orderId).getTransactionKey();

            // act & assert
            mockMvc.perform(confirm(orderId, user, "attempt-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.transactionKey").value(transactionKey));
            assertThat(PG.createCalls()).isEqualTo(1);
            assertThat(paymentJpaRepository.count()).isEqualTo(1);
            assertThat(stock()).isEqualTo(3);
        }

        @DisplayName("S-5 이미 시도한 주문에 다른 Idempotency-Key면 409다.")
        @Test
        void anotherKeyConflicts() throws Exception {
            // arrange
            Long orderId = pendingPayment("attempt-1");

            // act & assert
            mockMvc.perform(confirm(orderId, user, "attempt-2"))
                .andExpect(status().isConflict());
            assertThat(PG.createCalls()).isEqualTo(1);
        }

        @DisplayName("plan.md 2 Idempotency-Key 헤더가 없거나 비면 400이고, 주문은 DRAFT·재고 그대로다.")
        @Test
        void missingKeyIsBadRequest() throws Exception {
            // arrange
            Long orderId = createOrder();

            // act & assert
            mockMvc.perform(confirm(orderId, user, null)).andExpect(status().isBadRequest());
            mockMvc.perform(confirm(orderId, user, " ")).andExpect(status().isBadRequest());
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(stock()).isEqualTo(5);
            assertThat(paymentJpaRepository.count()).isZero();
        }

        @DisplayName("USR-01·ORD-06 X-USER-ID가 없으면 401, 남의 주문이면 404다.")
        @Test
        void identifiesOwner() throws Exception {
            // arrange
            Long orderId = createOrder();

            // act & assert
            mockMvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId)
                    .header(KEY_HEADER, "attempt-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CARD_BODY))
                .andExpect(status().isUnauthorized());
            mockMvc.perform(confirm(orderId, other, "attempt-1")).andExpect(status().isNotFound());
            assertThat(paymentJpaRepository.count()).isZero();
        }

        @DisplayName("ADR-W5-03 발송 풀이 가득 차면 202가 아니라 200으로 FAILED(NOT_SENT)·PAYMENT_FAILED를 바로 돌려주고, 재고를 반환한다.")
        @Test
        void fullPoolReturnsFailureImmediately() throws Exception {
            // arrange: 풀의 유일한 스레드가 첫 결제의 느린 생성 응답을 기다린다
            PG.delayNextCreateResponse(Duration.ofSeconds(2));
            Long first = createOrder();
            mockMvc.perform(confirm(first, user, "attempt-1")).andExpect(status().isAccepted());
            await().atMost(WAIT).until(() -> PG.createCalls() == 1);
            Long second = createOrder();

            // act & assert
            mockMvc.perform(confirm(second, user, "attempt-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value(Payment.NOT_SENT));
            await().atMost(WAIT).until(() -> paymentOf(first).getStatus() == PaymentStatus.UNKNOWN);
            assertThat(stock()).isEqualTo(3);
        }
    }

    @DisplayName("GET /api/v1/orders/{orderId}/payment")
    @Nested
    class GetPayment {

        @DisplayName("본인 주문의 결제 상태를 PG 호출 없이 돌려준다.")
        @Test
        void returnsStateWithoutPgCall() throws Exception {
            // arrange
            Long orderId = pendingPayment("attempt-1");
            int lookups = PG.lookupCalls();

            // act & assert
            mockMvc.perform(get("/api/v1/orders/{orderId}/payment", orderId).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.transactionKey").value(paymentOf(orderId).getTransactionKey()));
            assertThat(PG.lookupCalls()).isEqualTo(lookups);
        }

        @DisplayName("ORD-06 남의 주문이거나 결제 시도가 없는 주문이면 404다.")
        @Test
        void hidesOthersAndMissing() throws Exception {
            // arrange
            Long paid = pendingPayment("attempt-1");
            Long draft = createOrder();

            // act & assert
            mockMvc.perform(get("/api/v1/orders/{orderId}/payment", paid).header(USER_HEADER, other.getId()))
                .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/orders/{orderId}/payment", draft).header(USER_HEADER, user.getId()))
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/payment/reconcile")
    @Nested
    class Reconcile {

        @DisplayName("S-2 PG가 아직 PENDING이면 202, FAILED로 끝나면 200·PAYMENT_FAILED이고 재고가 돌아온다(3→5).")
        @Test
        void reconcilesNow() throws Exception {
            // arrange
            Long orderId = pendingPayment("attempt-1");
            String transactionKey = paymentOf(orderId).getTransactionKey();

            // act & assert
            mockMvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", orderId).header(USER_HEADER, user.getId()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"));

            PG.complete(transactionKey, "FAILED", "한도 초과");
            mockMvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", orderId).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("한도 초과"));
            assertThat(stock()).isEqualTo(5);
            assertThat(PG.createCalls()).isEqualTo(1);
        }

        @DisplayName("ORD-06 남의 주문이면 404이고 PG를 부르지 않는다.")
        @Test
        void hidesOthers() throws Exception {
            // arrange
            Long orderId = pendingPayment("attempt-1");
            int lookups = PG.lookupCalls();

            // act & assert
            mockMvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", orderId).header(USER_HEADER, other.getId()))
                .andExpect(status().isNotFound());
            assertThat(PG.lookupCalls()).isEqualTo(lookups);
        }
    }

    @DisplayName("POST /api/v1/payments/callback")
    @Nested
    class Callback {

        @DisplayName("S-6 PG가 X-USER-ID 없이 callback을 보내면 200이고, 권위 조회로 SUCCESS를 반영해 주문 CONFIRMED(PG)·재고 3이다. 중복 callback도 200이고 한 번만 반영된다.")
        @Test
        void appliesSuccessOnceByCallback() throws Exception {
            // arrange
            Long orderId = pendingPayment("attempt-1");
            String transactionKey = paymentOf(orderId).getTransactionKey();
            PG.complete(transactionKey, "SUCCESS", null);

            // act
            int first = PG.sendCallback(transactionKey);
            int duplicate = PG.sendCallback(transactionKey);

            // assert
            assertThat(first).isEqualTo(200);
            assertThat(duplicate).isEqualTo(200);
            assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            mockMvc.perform(get("/api/v1/orders/{orderId}", orderId).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data.paidAmount").value(2_000));
            assertThat(stock()).isEqualTo(3);
            assertThat(PG.createCalls()).isEqualTo(1);
        }

        @DisplayName("C-7 callback 본문의 상태를 믿지 않는다 — 본문이 SUCCESS여도 PG 조회가 PENDING이면 결제는 PENDING이다.")
        @Test
        void doesNotTrustCallbackBody() throws Exception {
            // arrange
            Long orderId = pendingPayment("attempt-1");
            Payment payment = paymentOf(orderId);

            // act & assert
            mockMvc.perform(post("/api/v1/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transactionKey\": \"" + payment.getTransactionKey() + "\", \"orderId\": \"" + payment.getProviderOrderId()
                        + "\", \"status\": \"SUCCESS\"}"))
                .andExpect(status().isOk());
            assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("모르는 PG 주문 식별자면 404다.")
        @Test
        void unknownOrderIsNotFound() throws Exception {
            // act & assert
            mockMvc.perform(post("/api/v1/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transactionKey\": \"20261021:TR:none\", \"orderId\": \"LP-ORD-0000009999\", \"status\": \"SUCCESS\"}"))
                .andExpect(status().isNotFound());
        }
    }
}
