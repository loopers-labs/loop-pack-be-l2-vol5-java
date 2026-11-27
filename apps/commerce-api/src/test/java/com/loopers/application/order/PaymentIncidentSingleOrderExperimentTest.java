package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 10주차 주문 한두 건 단위의 사고 실험. 처음에는 환경 확인용 probe(IncidentEnvironmentProbeTest)였고,
 * 생성 응답 유실이 남기는 상태·HTTP 요청 안의 traceId 확인에 더해 B(주문 한 건 복구), C(UNKNOWN 집계·gauge),
 * D 준비(주문 지정 응답 보류·주문별 생성 횟수) 실험을 담는다. 정상 10 + 장애 10 실험은 PaymentIncidentExperimentTest에 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@AutoConfigureObservability
@AutoConfigureMockMvc
@Import(PaymentIncidentSingleOrderExperimentTest.MdcCapture.class)
class PaymentIncidentSingleOrderExperimentTest {

    static final FakePaymentServer PG = startPg();
    static final AtomicReference<String> CAPTURED_TRACE_ID = new AtomicReference<>();

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orderFacade;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired private BrandJpaRepository brandJpaRepository;
    @Autowired private ProductJpaRepository productJpaRepository;
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @LocalManagementPort private int managementPort;

    private Long userId;
    private Long productId;

    @BeforeEach
    void setUp() {
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        CAPTURED_TRACE_ID.set(null);
        userId = registration.register().getId();
        Long brandId = brandJpaRepository.save(new BrandModel("나이키", null)).getId();
        productId = productJpaRepository.save(new ProductModel(brandId, "양말", 1_000L, 5)).getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void droppedCreateResponseLeavesUnknownWithReservedStock() {
        Long orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
        PG.dropNextCreateResponse();

        long started = System.nanoTime();
        orderFacade.confirm(userId, orderId, new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
        OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
        int stock = productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
        System.out.printf("[probe] confirm elapsed=%dms attempt=%s key=%s order=%s reservation=%s stock=%d "
                + "pgCreates=%d pgTx=%s%n",
            elapsed.toMillis(), attempt.getStatus(), attempt.getTransactionKey(), order.getStatus(),
            order.getReservationStatus(), stock, PG.createCalls(), PG.transactions());

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(attempt.getTransactionKey()).isNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(stock).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void onlySelectedOrderTimesOutWhenAnotherOrderIsConfirmedFirst(CapturedOutput output) throws IOException {
        var line = List.of(new OrderFacade.OrderLine(productId, 1));
        Long selectedId = orderFacade.create(userId, line).id();
        Long normalId = orderFacade.create(userId, line).id();
        String selectedPgId = "LP-ORD-%010d".formatted(selectedId);
        PG.settleCreatedAs("SUCCESS");
        PG.holdCreateResponseFor(selectedPgId);
        try {
            for (Long id : List.of(normalId, selectedId)) {
                orderFacade.confirm(userId, id, new OrderFacade.PaymentCommand("attempt-" + id, "TEST", "0000-0000-0000-0000"));
            }
            PaymentAttempt normal = paymentAttemptJpaRepository.findByOrderId(normalId).orElseThrow();
            PaymentAttempt selected = paymentAttemptJpaRepository.findByOrderId(selectedId).orElseThrow();
            var counts = PG.createCallsByOrder();
            System.out.printf("[targeted-create] normalOrder=%d attempt=%s selectedOrder=%d attempt=%s pgCreates=%d byOrder=%s%n",
                normalId, normal.getStatus(), selectedId, selected.getStatus(), PG.createCalls(), counts);
            assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(normal.getProviderOrderId(), 1, selectedPgId, 1));
            assertThat(normal.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(normal.getTransactionKey()).isNotBlank();
            assertThat(selected.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(selected.getTransactionKey()).isNull();
            assertThat(output).contains("PG 응답: op=create providerOrderId=" + normal.getProviderOrderId() + " httpStatus=202",
                "PG 응답 없음: op=create providerOrderId=" + selectedPgId
                    + " result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException");
            assertThat(PG.createCalls()).isEqualTo(2);
            assertThat(PG.transactions()).extracting(tx -> tx.path("orderId").asText())
                .containsExactlyInAnyOrder(selectedPgId, normal.getProviderOrderId());
            assertThat(PG.transactions()).allSatisfy(tx -> assertThat(tx.path("status").asText()).isEqualTo("SUCCESS"));
        } finally {
            PG.releaseCreateResponseFor(selectedPgId);
            PG.settleCreatedAs(null);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout(CapturedOutput output) throws Exception {
        Long orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
        assertUnknownCount("before-confirm", 0);
        PG.settleCreatedAs("SUCCESS");
        PG.holdNextCreateResponse();
        try {
            orderFacade.confirm(userId, orderId,
                new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
            assertUnknownCount("after-timeout", 1);

            PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
            String providerOrderId = attempt.getProviderOrderId();
            PaymentObservation lookup = paymentProvider.findByOrderId(providerOrderId);
            OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
            int stock = productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
            System.out.printf("[before-reconcile] at=%s orderId=%d providerOrderId=%s attemptKey=%s attempt=%s "
                    + "internalTxKey=%s order=%s orderAmount=%d reservation=%s stock=%d pgCreates=%d pgLookups=%d%n",
                Instant.now(), orderId, providerOrderId, attempt.getAttemptKey(), attempt.getStatus(),
                attempt.getTransactionKey(), order.getStatus(), order.getTotalAmount(), order.getReservationStatus(),
                stock, PG.createCalls(), PG.lookupCalls());

            assertThat(output).contains("PG 응답 없음: op=create providerOrderId=" + providerOrderId
                + " result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException");
            assertThat(lookup.kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
            assertThat(lookup.transactions()).singleElement().satisfies(transaction -> {
                assertThat(transaction.transactionKey()).isNotBlank();
                assertThat(transaction.providerOrderId()).isEqualTo(providerOrderId);
                assertThat(transaction.amount()).isEqualTo(order.getTotalAmount()).isEqualTo(attempt.getAmount());
                assertThat(transaction.status()).isEqualTo(PaymentTransaction.Status.SUCCESS);
            });
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock).isEqualTo(2);
            assertThat(PG.createCalls()).isOne();
            assertThat(PG.lookupCalls()).isOne();

            reconcileAndVerify(orderId, lookup.transactions().getFirst(), stock, PG.createCalls());
            assertUnknownCount("after-reconcile", 0);
        } finally {
            PG.settleCreatedAs(null);
        }
    }

    private void assertUnknownCount(String phase, long expected) throws Exception {
        long actual = paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN);
        long observed = System.nanoTime();
        System.out.printf("[unknown-count] at=%s phase=%s count=%d%n", Instant.now(), phase, actual);
        assertThat(actual).as("DB UNKNOWN count at %s", phase).isEqualTo(expected);

        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
            .header("Accept", "text/plain").timeout(Duration.ofSeconds(2)).GET().build();
        String name = "commerce_payment_attempts_unknown";
        String sample = "";
        try (HttpClient client = HttpClient.newHttpClient()) {
            do {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).as("Prometheus gauge type").contains("# TYPE " + name + " gauge");
                sample = response.body().lines().filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
                    .findFirst().orElseThrow();
                double value = Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1));
                if (value == actual) {
                    System.out.printf("[unknown-gauge] at=%s phase=%s observedAfterMs=%d sample=%s%n",
                        Instant.now(), phase, Duration.ofNanos(System.nanoTime() - observed).toMillis(), sample);
                    return;
                }
                Thread.sleep(100);
            } while (System.nanoTime() - observed < Duration.ofSeconds(12).toNanos());
        }
        throw new AssertionError("Prometheus gauge at " + phase + " expected " + actual + ", last sample " + sample);
    }

    private void reconcileAndVerify(Long orderId, PaymentTransaction original, int stockBefore, int createsBefore)
        throws Exception {
        var response = mvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", orderId)
                .header("X-USER-ID", userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderId").value(orderId))
            .andExpect(jsonPath("$.data.checked").value(true))
            .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.reservationStatus").value("CONSUMED"))
            .andReturn().getResponse();

        PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
        OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
        int stock = productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
        System.out.printf("[after-reconcile] at=%s orderId=%d httpStatus=%d attempt=%s internalTxKey=%s "
                + "order=%s paidAmount=%d reservation=%s stock=%d pgCreates=%d pgLookups=%d%n",
            Instant.now(), orderId, response.getStatus(), attempt.getStatus(), attempt.getTransactionKey(),
            order.getStatus(), order.getPaidAmount(), order.getReservationStatus(), stock, PG.createCalls(), PG.lookupCalls());
        assertThat(PG.lookupCalls()).isEqualTo(2);
        assertThat(PG.requests().getLast().rawQuery()).isEqualTo("orderId=" + original.providerOrderId());
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(attempt.getTransactionKey()).isEqualTo(original.transactionKey());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(original.amount());
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(stock).isEqualTo(stockBefore);
        assertThat(PG.createCalls()).isEqualTo(createsBefore);
        assertThat(PG.transactions()).singleElement()
            .satisfies(transaction -> assertThat(transaction.path("transactionKey").asText()).isEqualTo(original.transactionKey()));
    }

    @Test
    void recordsTraceIdSeenInsideHttpRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
            .content("{\"providerOrderId\":\"LP-ORD-9999999999\",\"transactionKey\":\"x\",\"status\":\"SUCCESS\"}"));

        System.out.printf("[probe] %s traceId in MDC=%s%n", getClass().getSimpleName(), CAPTURED_TRACE_ID.get());
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @TestConfiguration
    static class MdcCapture {
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> mdcCaptureFilter() {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                    CAPTURED_TRACE_ID.set(MDC.get("traceId"));
                    chain.doFilter(request, response);
                }
            });
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }
    }
}
