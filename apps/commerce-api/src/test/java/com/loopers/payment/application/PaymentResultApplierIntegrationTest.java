package com.loopers.payment.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.common.domain.Money;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.domain.ExternalPaymentStatus;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentApplyOutcome;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.application.port.in.StockReservationUseCase;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.concurrency.ConcurrentRunner;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 결과 적용 경계 (plan.md 4장·4-1, ADR-W5-04). PG 권위 조회 → payment 잠금 → 상태 가드 → APPLIED일 때만 주문·선점 반영.
 * 자동 발송은 끄고(mock), 테스트가 PG에 거래를 직접 만든 뒤 결제를 PENDING으로 맞춰 놓고 시작한다.
 * 재고 예: 재고 5 · 수량 2 선점 → 3. 성공 후 3, 실패 후 5. 중복 실패로 7이 되면 버그 (plan.md 10장).
 */
@SpringBootTest(properties = "pg.local.lookup-read-timeout=300ms")
class PaymentResultApplierIntegrationTest {

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
    private PaymentResultApplier paymentResultApplier;

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    @MockitoBean
    private PaymentDispatchUseCase paymentDispatchUseCase;

    @MockitoSpyBean
    private StockReservationUseCase stockReservationUseCase;

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    @Autowired
    private PaymentPort paymentPort;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel airMax;
    private PaymentInfo info;
    private String transactionKey;

    @BeforeEach
    void setUp() {
        PG.reset();
        circuitBreakerRegistry.circuitBreaker("pgLookup").reset();
        user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
        info = pendingPayment();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 재고 5에서 2개(2,000원)를 주문·선점하고, PG에 거래를 만든 뒤 결제를 PENDING으로 맞춘다. */
    private PaymentInfo pendingPayment() {
        Long orderId = orderCommandUseCase.create(user.getId(), List.of(new OrderLines.Line(airMax.getId(), 2))).id();
        PaymentInfo requested = paymentRequestUseCase.request(new PaymentRequestCommand(user.getId(), orderId, "attempt-1", "SAMSUNG", "1234-5678-9814-1451"));
        Payment payment = paymentPort.findById(requested.paymentId()).orElseThrow();
        PgTransaction created = paymentGatewayPort.request(new PgPaymentRequest(payment.getProviderOrderId(), "SAMSUNG", "1234-5678-9814-1451", 2_000L));
        transactionKey = created.transactionKey();
        transactionTemplate.executeWithoutResult(status -> {
            Payment locked = paymentPort.findByIdForUpdate(requested.paymentId()).orElseThrow();
            locked.markSending(ZonedDateTime.now());
            locked.recordAccepted(transactionKey, ZonedDateTime.now());
            paymentPort.save(locked);
        });
        return requested;
    }

    private Payment payment() {
        return paymentJpaRepository.findById(info.paymentId()).orElseThrow();
    }

    private OrderModel order() {
        return orderJpaRepository.findById(info.orderId()).orElseThrow();
    }

    private int stock() {
        return productJpaRepository.findById(airMax.getId()).orElseThrow().getStock();
    }

    private List<String> reservationRows() {
        return jdbcTemplate.queryForList("SELECT status FROM stock_reservations WHERE order_id = ?", String.class, info.orderId());
    }

    @DisplayName("PG 조회로 결과를 반영할 때 (reconcile), ")
    @Nested
    class Reconcile {

        @DisplayName("S-1 PG가 SUCCESS면 결제 SUCCESS·주문 CONFIRMED(결제액 2,000)·선점 CONSUMED이고, 재고는 더 줄지 않는다(3).")
        @Test
        void successConfirmsOrderAndConsumes() {
            // arrange
            PG.complete(transactionKey, "SUCCESS", null);

            // act
            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment().getNextCheckAt()).isNull();
            assertThat(order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order().getPaidAmount()).isEqualTo(Money.of(2_000));
            assertThat(order().getConfirmedAt()).isNotNull();
            assertThat(reservationRows()).containsExactly("CONSUMED");
            assertThat(stock()).isEqualTo(3);
            assertThat(PG.createCalls()).isEqualTo(1);
        }

        @DisplayName("S-2 PG가 아직 PENDING이면 결제·주문·선점 모두 그대로이고, 조회 기록만 남는다.")
        @Test
        void pendingKeepsEverything() {
            // act
            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment().getCheckCount()).isEqualTo(1);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(reservationRows()).containsExactly("RESERVED");
            assertThat(stock()).isEqualTo(3);
        }

        @DisplayName("S-3 PG가 FAILED면 결제 FAILED(사유)·주문 PAYMENT_FAILED·선점 RELEASED이고, 재고가 한 번 돌아온다(3→5).")
        @Test
        void failureFailsOrderAndReleases() {
            // arrange
            PG.complete(transactionKey, "FAILED", "한도 초과");

            // act
            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment().getFailureReason()).isEqualTo("한도 초과");
            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(reservationRows()).containsExactly("RELEASED");
            assertThat(stock()).isEqualTo(5);
        }

        @DisplayName("S-9 거래 키로 찾은 거래가 없으면(PG 404) 상태를 바꾸지 않고 NOT_FOUND로 기록한다 — 실패로 닫지 않는다.")
        @Test
        void notFoundIsRecordedOnly() {
            // arrange
            PG.reset();

            // act
            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment().getLastCheckResult()).isEqualTo("NOT_FOUND");
            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stock()).isEqualTo(3);
            assertThat(PG.createCalls()).isZero();
        }

        @DisplayName("S-10 PG 조회가 계속 실패하면(500) 상태를 바꾸지 않고 UNAVAILABLE로 기록한다.")
        @Test
        void unavailableIsRecordedOnly() {
            // arrange
            PG.failNextLookups(3);

            // act
            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment().getLastCheckResult()).isEqualTo("UNAVAILABLE");
            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }
    }

    @DisplayName("같은 결과가 여러 번 와도 한 번만 반영한다 (plan.md 4-1), ")
    @Nested
    class Idempotency {

        @DisplayName("R-1 같은 SUCCESS를 두 번 반영하면 두 번째는 ALREADY_APPLIED이고, 확정·소비는 각 1번이며 재고는 3이다.")
        @Test
        void sameSuccessTwice() {
            // arrange
            PG.complete(transactionKey, "SUCCESS", null);
            paymentResultApplier.reconcile(info.paymentId());
            ZonedDateTime confirmedAt = order().getConfirmedAt();

            // act
            PaymentApplyOutcome second = paymentResultApplier.reconcile(info.paymentId());

            // assert
            assertThat(second).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order().getConfirmedAt()).isEqualTo(confirmedAt);
            assertThat(reservationRows()).containsExactly("CONSUMED");
            assertThat(stock()).isEqualTo(3);
        }

        @DisplayName("R-2 같은 FAILED를 두 번 반영해도 재고는 5→3→5이고 7이 되지 않는다.")
        @Test
        void sameFailureTwice() {
            // arrange
            PG.complete(transactionKey, "FAILED", "한도 초과");
            paymentResultApplier.reconcile(info.paymentId());

            // act
            PaymentApplyOutcome second = paymentResultApplier.reconcile(info.paymentId());

            // assert
            assertThat(second).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(reservationRows()).containsExactly("RELEASED");
            assertThat(stock()).isEqualTo(5);
        }

        @DisplayName("R-3 10개 스레드가 같은 SUCCESS를 동시에 반영하면 APPLIED 1·ALREADY_APPLIED 9이고, 확정·소비는 1번이다.")
        @Test
        void concurrentSuccess() throws InterruptedException {
            // arrange
            PG.complete(transactionKey, "SUCCESS", null);
            List<PaymentApplyOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
            List<Callable<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tasks.add(() -> outcomes.add(paymentResultApplier.reconcile(info.paymentId())));
            }

            // act
            ConcurrentRunner.run(tasks);

            // assert
            assertThat(outcomes).hasSize(10);
            assertThat(outcomes).filteredOn(outcome -> outcome == PaymentApplyOutcome.APPLIED).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome == PaymentApplyOutcome.ALREADY_APPLIED).hasSize(9);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reservationRows()).containsExactly("CONSUMED");
            assertThat(stock()).isEqualTo(3);
        }

        @DisplayName("R-4 SUCCESS 뒤에 FAILED가 보이면 CONFLICT이고, 결제·주문·재고는 그대로이며 이상을 기록한다.")
        @Test
        void failureAfterSuccessConflicts() {
            // arrange
            PG.complete(transactionKey, "SUCCESS", null);
            paymentResultApplier.reconcile(info.paymentId());
            PG.complete(transactionKey, "FAILED", "취소");

            // act
            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.CONFLICT);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment().getLastCheckResult()).startsWith("CONFLICT");
            assertThat(order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reservationRows()).containsExactly("CONSUMED");
            assertThat(stock()).isEqualTo(3);
        }

        @DisplayName("R-5 반영 중 저장이 실패하면 결제·주문·선점이 모두 rollback되고, 다시 조회해 반영하면 정상 확정된다 (create 추가 0).")
        @Test
        void failureMidApplyRollsBackThenReapplies() {
            // arrange
            PG.complete(transactionKey, "SUCCESS", null);
            // 트랜잭션 프록시 안쪽의 spy에 직접 정한다 — 프록시로 부르면 MANDATORY 검사가 먼저 실행된다
            StockReservationUseCase spy = AopTestUtils.getUltimateTargetObject(stockReservationUseCase);
            doThrow(new IllegalStateException("선점 저장 실패")).doCallRealMethod().when(spy).consume(anyLong());

            // act & assert: 첫 반영은 실패하고 아무것도 남기지 않는다
            assertThatThrownBy(() -> paymentResultApplier.reconcile(info.paymentId()))
                .isInstanceOf(IllegalStateException.class);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(reservationRows()).containsExactly("RESERVED");

            // act & assert: 같은 외부 결과를 다시 조회해 반영한다
            PaymentApplyOutcome retried = paymentResultApplier.reconcile(info.paymentId());
            assertThat(retried).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reservationRows()).containsExactly("CONSUMED");
            assertThat(stock()).isEqualTo(3);
            assertThat(PG.createCalls()).isEqualTo(1);
        }
    }

    @DisplayName("PG 관찰을 직접 반영할 때 (생성 응답·callback 공통 경계), ")
    @Nested
    class Apply {

        @DisplayName("C-7 관찰한 금액이 저장 금액과 다르면 MISMATCH이고 상태를 바꾸지 않는다.")
        @Test
        void amountMismatchKeepsState() {
            // arrange
            PgTransaction wrongAmount = new PgTransaction(transactionKey, payment().getProviderOrderId(), 1L, ExternalPaymentStatus.SUCCESS, null);

            // act
            PaymentApplyOutcome outcome = paymentResultApplier.apply(info.paymentId(), wrongAmount);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.MISMATCH);
            assertThat(payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stock()).isEqualTo(3);
        }
    }
}
