package com.loopers.payment.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.adapter.out.persistence.PaymentOutboxJpaRepository;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentOutboxStatus;
import com.loopers.payment.domain.PaymentStatus;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * commit 뒤 발송 (plan.md 4장·5-1, ADR-W5-01·03). 로컬 테스트 PG fixture로 생성 응답을 바꿔 가며 TX B 반영을 확인한다.
 * 발송은 비동기라 Awaitility로 기다린다. 풀 거절을 재현하려고 발송 풀을 1개로 줄이고, 생성 읽기 timeout을 500ms로 줄인다.
 */
@SpringBootTest(properties = {
    "pg.local.create-read-timeout=500ms",
    "pg.local.lookup-read-timeout=300ms",
    "payment.dispatch.pool-size=1",
})
class PaymentDispatchIntegrationTest {

    private static final LocalTestPgServer PG = LocalTestPgServer.start();
    private static final Duration WAIT = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

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
    private PaymentOutboxJpaRepository paymentOutboxJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel airMax;

    @BeforeEach
    void setUp() {
        PG.reset();
        circuitBreakerRegistry.circuitBreaker("pgCreate").reset();
        circuitBreakerRegistry.circuitBreaker("pgLookup").reset();
        user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 재고 5에서 2개(2,000원)를 주문하고 결제를 요청한다. */
    private PaymentInfo requestPayment(String attemptKey) {
        Long orderId = orderCommandUseCase.create(user.getId(), List.of(new OrderLines.Line(airMax.getId(), 2))).id();
        return paymentRequestUseCase.request(new PaymentRequestCommand(user.getId(), orderId, attemptKey, "SAMSUNG", "1234-5678-9814-1451"));
    }

    private Payment paymentOf(PaymentInfo info) {
        return paymentJpaRepository.findById(info.paymentId()).orElseThrow();
    }

    private PaymentStatus statusOf(PaymentInfo info) {
        return paymentOf(info).getStatus();
    }

    private PaymentOutboxStatus outboxStatusOf(PaymentInfo info) {
        return paymentOutboxJpaRepository.findAll().stream()
            .filter(outbox -> outbox.getPaymentId().equals(info.paymentId()))
            .findFirst().orElseThrow().getStatus();
    }

    private OrderStatus orderStatusOf(PaymentInfo info) {
        return orderJpaRepository.findById(info.orderId()).orElseThrow().getStatus();
    }

    private int stock() {
        return productJpaRepository.findById(airMax.getId()).orElseThrow().getStock();
    }

    private List<String> reservationRows(PaymentInfo info) {
        return jdbcTemplate.queryForList("SELECT status FROM stock_reservations WHERE order_id = ?", String.class, info.orderId());
    }

    @DisplayName("S-2 PG가 생성을 수락하면 결제 PENDING(거래 키)·장부 DONE이 되고, 주문은 미확정·재고 선점 유지(5→3)다.")
    @Test
    void acceptedBecomesPending() {
        // act
        PaymentInfo info = requestPayment("attempt-1");

        // assert
        await().atMost(WAIT).until(() -> statusOf(info) == PaymentStatus.PENDING);
        Payment payment = paymentOf(info);
        assertThat(payment.getTransactionKey()).isNotBlank();
        assertThat(PG.transaction(payment.getTransactionKey())).isPresent();
        assertThat(payment.getNextCheckAt()).isNotNull();
        await().atMost(WAIT).until(() -> outboxStatusOf(info) == PaymentOutboxStatus.DONE);
        assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(stock()).isEqualTo(3);
        assertThat(reservationRows(info)).containsExactly("RESERVED");
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.createRecords().get(0).orderId()).isEqualTo(payment.getProviderOrderId());
        assertThat(PG.createRecords().get(0).amount()).isEqualTo(2_000L);
    }

    @DisplayName("S-3 PG가 거래를 만들지 않고 거절하면 결제 FAILED·주문 PAYMENT_FAILED이고, 재고는 한 번 반환된다(3→5).")
    @Test
    void rejectedFailsAndReleasesOnce() {
        // arrange
        PG.rejectNextCreate();

        // act
        PaymentInfo info = requestPayment("attempt-1");

        // assert
        await().atMost(WAIT).until(() -> statusOf(info) == PaymentStatus.FAILED);
        assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(stock()).isEqualTo(5);
        assertThat(reservationRows(info)).containsExactly("RELEASED");
        await().atMost(WAIT).until(() -> outboxStatusOf(info) == PaymentOutboxStatus.DONE);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isZero();
    }

    @DisplayName("S-4 거래를 만든 뒤 응답만 유실되면(timeout) UNKNOWN이고, 다시 보내지 않으며(create 1) 재고 선점은 유지된다.")
    @Test
    void lostResponseBecomesUnknownWithoutResend() throws InterruptedException {
        // arrange
        PG.delayNextCreateResponse(Duration.ofMillis(1_500));

        // act
        PaymentInfo info = requestPayment("attempt-1");

        // assert
        await().atMost(WAIT).until(() -> statusOf(info) == PaymentStatus.UNKNOWN);
        await().atMost(WAIT).until(() -> outboxStatusOf(info) == PaymentOutboxStatus.DONE);
        Thread.sleep(1_500); // 늦게 온 응답이나 재전송이 있는지 지켜본다
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isEqualTo(1);
        assertThat(statusOf(info)).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(stock()).isEqualTo(3);
        assertThat(reservationRows(info)).containsExactly("RESERVED");
    }

    @DisplayName("C-6 PG가 거래를 만든 뒤 500으로 답해도 UNKNOWN이고 실패로 닫지 않는다 (재고 선점 유지).")
    @Test
    void serverErrorAfterCreateBecomesUnknown() {
        // arrange
        PG.failNextCreateAfterCreating();

        // act
        PaymentInfo info = requestPayment("attempt-1");

        // assert
        await().atMost(WAIT).until(() -> statusOf(info) == PaymentStatus.UNKNOWN);
        assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(stock()).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @DisplayName("ADR-W5-03 생성 회로가 열려 있으면 보내지 않고 바로 FAILED(NOT_SENT)로 닫고 재고를 반환한다 (create 0).")
    @Test
    void openCircuitClosesAsNotSent() {
        // arrange
        circuitBreakerRegistry.circuitBreaker("pgCreate").transitionToOpenState();

        // act
        PaymentInfo info = requestPayment("attempt-1");

        // assert
        await().atMost(WAIT).until(() -> statusOf(info) == PaymentStatus.FAILED);
        assertThat(paymentOf(info).getFailureReason()).isEqualTo(Payment.NOT_SENT);
        assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(stock()).isEqualTo(5);
        assertThat(reservationRows(info)).containsExactly("RELEASED");
        assertThat(PG.createCalls()).isZero();
    }

    @DisplayName("ADR-W5-03 발송 풀이 가득 차면 요청이 돌아오기 전에 FAILED(NOT_SENT)·장부 CLOSED로 닫고 재고를 반환한다 (그 결제의 create 0).")
    @Test
    void fullPoolClosesImmediately() {
        // arrange: 풀의 유일한 스레드가 첫 결제의 느린 생성 응답(읽기 timeout 500ms)을 기다리게 한다
        PG.delayNextCreateResponse(Duration.ofSeconds(2));
        PaymentInfo first = requestPayment("attempt-1");
        await().atMost(WAIT).until(() -> PG.createCalls() == 1);

        // act
        PaymentInfo second = requestPayment("attempt-2");

        // assert: 기다리지 않고 바로 확인한다 — 요청 스레드에서 이미 닫혔다
        assertThat(statusOf(second)).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentOf(second).getFailureReason()).isEqualTo(Payment.NOT_SENT);
        assertThat(outboxStatusOf(second)).isEqualTo(PaymentOutboxStatus.CLOSED);
        assertThat(orderStatusOf(second)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationRows(second)).containsExactly("RELEASED");
        await().atMost(WAIT).until(() -> statusOf(first) == PaymentStatus.UNKNOWN);
        assertThat(stock()).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }
}
