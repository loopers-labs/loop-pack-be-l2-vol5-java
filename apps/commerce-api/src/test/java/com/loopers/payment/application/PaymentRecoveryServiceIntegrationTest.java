package com.loopers.payment.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.adapter.out.persistence.PaymentOutboxJpaRepository;
import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRecoveryUseCase;
import com.loopers.payment.application.port.in.PaymentRecoveryUseCase.StaleDispatchResult;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.application.port.out.PgTransaction;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정리 스케줄러와 reconcile의 판단 (plan.md 5-1, ADR-W5-03). 스케줄러 빈은 test 프로필에서 꺼져 있어 서비스를 직접 부르고, "지금"을 인자로 넘긴다.
 * 자동 발송은 끄고(mock) "이벤트를 잃은 결제"(장부 PENDING)와 "보내다 멈춘 결제"(장부 CLAIMED·결제 SENDING)를 직접 만든다.
 */
@SpringBootTest(properties = "pg.local.lookup-read-timeout=300ms")
class PaymentRecoveryServiceIntegrationTest {

    private static final LocalTestPgServer PG = LocalTestPgServer.start();

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    @Autowired
    private PaymentRecoveryUseCase paymentRecoveryUseCase;

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    @MockitoBean
    private PaymentDispatchUseCase paymentDispatchUseCase;

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    @Autowired
    private PaymentPort paymentPort;

    @Autowired
    private PaymentOutboxPort paymentOutboxPort;

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
        circuitBreakerRegistry.circuitBreaker("pgLookup").reset();
        user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 재고 5에서 2개(2,000원)를 선점한 결제. 발송 이벤트를 잃어 장부는 PENDING으로 남는다. */
    private PaymentInfo lostEventPayment(String attemptKey) {
        Long orderId = orderCommandUseCase.create(user.getId(), List.of(new OrderLines.Line(airMax.getId(), 2))).id();
        return paymentRequestUseCase.request(new PaymentRequestCommand(user.getId(), orderId, attemptKey, "SAMSUNG", "1234-5678-9814-1451"));
    }

    /** 발송 권한을 얻고(장부 CLAIMED·결제 SENDING) PG 호출 전후에 서버가 멈춘 결제. */
    private PaymentInfo stuckSendingPayment(String attemptKey) {
        PaymentInfo info = lostEventPayment(attemptKey);
        transactionTemplate.executeWithoutResult(status -> {
            paymentOutboxPort.claim(info.paymentId(), ZonedDateTime.now());
            Payment payment = paymentPort.findByIdForUpdate(info.paymentId()).orElseThrow();
            payment.markSending(ZonedDateTime.now());
            paymentPort.save(payment);
        });
        return info;
    }

    /** 보낸 뒤 결과를 모르는(UNKNOWN, 거래 키 없음) 결제. PG에는 transactions개의 거래가 있다. */
    private PaymentInfo unknownPayment(String attemptKey, int transactions) {
        PaymentInfo info = stuckSendingPayment(attemptKey);
        Payment payment = paymentOf(info);
        for (int i = 0; i < transactions; i++) {
            paymentGatewayPort.request(new PgPaymentRequest(payment.getProviderOrderId(), "SAMSUNG", "1234-5678-9814-1451", 2_000L));
        }
        transactionTemplate.executeWithoutResult(status -> {
            Payment locked = paymentPort.findByIdForUpdate(info.paymentId()).orElseThrow();
            locked.recordUnknown(ZonedDateTime.now());
            paymentPort.save(locked);
        });
        return info;
    }

    private Payment paymentOf(PaymentInfo info) {
        return paymentJpaRepository.findById(info.paymentId()).orElseThrow();
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

    @DisplayName("정리 스케줄러 (PaymentDispatchCloser), ")
    @Nested
    class CloseStaleDispatches {

        @DisplayName("ADR-W5-03 30초 넘게 아무도 발송하지 않은 결제는 FAILED(NOT_SENT)·장부 CLOSED·주문 PAYMENT_FAILED이고 재고가 돌아온다(3→5). PG 호출 0.")
        @Test
        void closesUnclaimedAfter30Seconds() {
            // arrange
            PaymentInfo info = lostEventPayment("attempt-1");

            // act
            StaleDispatchResult result = paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusSeconds(31));

            // assert
            assertThat(result.closedNotSent()).isEqualTo(1);
            assertThat(paymentOf(info).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(paymentOf(info).getFailureReason()).isEqualTo(Payment.NOT_SENT);
            assertThat(outboxStatusOf(info)).isEqualTo(PaymentOutboxStatus.CLOSED);
            assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(reservationRows(info)).containsExactly("RELEASED");
            assertThat(stock()).isEqualTo(5);
            assertThat(PG.createCalls()).isZero();
        }

        @DisplayName("ADR-W5-03 저장한 지 30초가 안 된 결제는 그대로 둔다.")
        @Test
        void keepsFreshUnclaimed() {
            // arrange
            PaymentInfo info = lostEventPayment("attempt-1");

            // act
            StaleDispatchResult result = paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusSeconds(20));

            // assert
            assertThat(result.closedNotSent()).isZero();
            assertThat(paymentOf(info).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(outboxStatusOf(info)).isEqualTo(PaymentOutboxStatus.PENDING);
            assertThat(stock()).isEqualTo(3);
        }

        @DisplayName("S-8 발송 권한만 쥐고 30초 넘게 결과가 없는 결제는 UNKNOWN·장부 DONE이고, 다시 보내지 않으며 재고 선점은 유지된다.")
        @Test
        void marksStuckSendingUnknownWithoutResend() {
            // arrange
            PaymentInfo info = stuckSendingPayment("attempt-1");

            // act
            StaleDispatchResult result = paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusSeconds(31));

            // assert
            assertThat(result.markedUnknown()).isEqualTo(1);
            assertThat(paymentOf(info).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(paymentOf(info).getNextCheckAt()).isNotNull();
            assertThat(outboxStatusOf(info)).isEqualTo(PaymentOutboxStatus.DONE);
            assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(reservationRows(info)).containsExactly("RESERVED");
            assertThat(stock()).isEqualTo(3);
            assertThat(PG.createCalls()).isZero();
        }

        @DisplayName("ADR-W5-03 정리가 먼저 닫은 결제는 늦게 온 발송이 권한을 얻지 못한다 (조건부 claim).")
        @Test
        void closedPaymentCannotBeClaimedLater() {
            // arrange
            PaymentInfo info = lostEventPayment("attempt-1");
            paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusSeconds(31));

            // act
            boolean claimed = paymentOutboxPort.claim(info.paymentId(), ZonedDateTime.now());

            // assert
            assertThat(claimed).isFalse();
            assertThat(paymentOf(info).getStatus()).isEqualTo(PaymentStatus.FAILED);
        }
    }

    @DisplayName("reconcile (PaymentReconcileScheduler), ")
    @Nested
    class ReconcileDue {

        @DisplayName("S-4 거래 키 없이 UNKNOWN인 결제는 PG 주문 식별자로 거래 1건을 찾아 PENDING(거래 키)으로 기록하고, SUCCESS가 되면 확정한다. 생성 추가 0.")
        @Test
        void findsSingleTransactionThenConfirms() {
            // arrange
            PaymentInfo info = unknownPayment("attempt-1", 1);

            // act
            int checked = paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusSeconds(6));

            // assert
            assertThat(checked).isEqualTo(1);
            Payment pending = paymentOf(info);
            assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(pending.getTransactionKey()).isNotBlank();

            // act: PG가 성공으로 끝낸 뒤 다음 조회
            PG.complete(pending.getTransactionKey(), "SUCCESS", null);
            paymentRecoveryUseCase.reconcileDue(pending.getNextCheckAt().plusSeconds(1));

            // assert
            assertThat(paymentOf(info).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reservationRows(info)).containsExactly("CONSUMED");
            assertThat(stock()).isEqualTo(3);
            assertThat(PG.createCalls()).isEqualTo(1);
        }

        @DisplayName("plan.md 5-1 다음 조회 시각이 아직이면 PG를 부르지 않는다.")
        @Test
        void skipsNotDue() {
            // arrange
            PaymentInfo info = unknownPayment("attempt-1", 1);

            // act
            int checked = paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().minusSeconds(1));

            // assert
            assertThat(checked).isZero();
            assertThat(paymentOf(info).getCheckCount()).isZero();
            assertThat(PG.lookupCalls()).isZero();
        }

        @DisplayName("S-9 PG에 거래가 0건이면 실패로 닫지 않고 NOT_FOUND를 기록하며, 다음 조회를 10초 뒤로 미룬다. 재고 선점 유지.")
        @Test
        void zeroTransactionsIsRecordedOnly() {
            // arrange
            PaymentInfo info = unknownPayment("attempt-1", 0);
            ZonedDateTime checkedFrom = ZonedDateTime.now();

            // act: 대상 고르기만 6초 뒤로 당기고, 조회 기록은 실제 조회 시각으로 남는다
            paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusSeconds(6));

            // assert
            ZonedDateTime checkedUntil = ZonedDateTime.now();
            Payment payment = paymentOf(info);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(payment.getLastCheckResult()).isEqualTo("NOT_FOUND");
            assertThat(payment.getNextCheckAt().toInstant())
                .isBetween(checkedFrom.plusSeconds(10).toInstant(), checkedUntil.plusSeconds(10).toInstant());
            assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stock()).isEqualTo(3);
            assertThat(PG.createCalls()).isZero();
        }

        @DisplayName("S-9 PG에 같은 주문의 거래가 2건이면 임의로 고르지 않고 MULTIPLE:2를 기록한다. 상태·재고 그대로.")
        @Test
        void multipleTransactionsAreRecordedOnly() {
            // arrange
            PaymentInfo info = unknownPayment("attempt-1", 2);
            List<PgTransaction> transactions = paymentGatewayPort.findByProviderOrderId(paymentOf(info).getProviderOrderId());
            PG.complete(transactions.get(0).transactionKey(), "SUCCESS", null);

            // act
            paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusSeconds(6));

            // assert
            assertThat(paymentOf(info).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(paymentOf(info).getLastCheckResult()).isEqualTo("MULTIPLE:2");
            assertThat(orderStatusOf(info)).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stock()).isEqualTo(3);
            assertThat(PG.createCalls()).isEqualTo(2);
        }
    }
}
