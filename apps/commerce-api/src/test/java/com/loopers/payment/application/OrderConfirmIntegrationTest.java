package com.loopers.payment.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.common.domain.Money;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.PaymentMethod;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentReconcileUseCase;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.point.adapter.out.persistence.PointGroupJpaRepository;
import com.loopers.point.adapter.out.persistence.PointHistoryJpaRepository;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.point.domain.PointGroup;
import com.loopers.point.domain.PointHistory;
import com.loopers.point.domain.PointHistoryType;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 주문 확정 (ORD-02~05) — W5 이전 OrderCommandServiceConfirmIntegrationTest(포인트 확정)를 PG 흐름으로 옮겼다 (W5 plan.md 10-1).
 * 확정 = 결제 요청 TX A(주문·재고 선점·결제 시도·장부) → 발송 → PG 결과 확인(reconcile) → 결과 반영. 로컬 테스트 PG fixture가 결과를 정한다.
 * 실제 빈과 테스트 DB로, 확정 뒤 DB에 남은 재고·선점·주문 상태와 "포인트 그룹·사용 이력이 그대로인지(C-1)"를 확인한다.
 */
// W5: PG fixture 주소(또는 발송 mock) 때문에 이 클래스만 쓰는 컨텍스트가 생긴다. 다른 클래스가 재사용하지 않으므로 끝나면 닫아
// DB 커넥션을 돌려준다 — 캐시된 컨텍스트마다 Hikari 풀을 잡아 로컬 MySQL max_connections(151)를 넘기지 않게.
@DirtiesContext
@SpringBootTest
class OrderConfirmIntegrationTest {

    private static final String CARD_TYPE = "SAMSUNG";
    private static final String CARD_NO = "1234-5678-9814-1451";
    private static final Duration WAIT = Duration.ofSeconds(5);
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
    private PaymentRequestUseCase paymentRequestUseCase;

    @Autowired
    private PaymentReconcileUseCase paymentReconcileUseCase;

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private PointCommandUseCase pointCommandUseCase;

    @MockitoSpyBean
    private PaymentOutboxPort paymentOutboxPort;

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
    private PointGroupJpaRepository pointGroupJpaRepository;

    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel airMax;
    private ProductModel airForce;

    @BeforeEach
    void setUp() {
        PG.reset();
        user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 10));
        airForce = productJpaRepository.save(new ProductModel(nike.getId(), "에어포스", 2_000, 10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 에어맥스 1,000원 × 3 + 에어포스 2,000원 × 2 = 7,000원짜리 DRAFT 주문. */
    private Long createOrderOf7000(UserModel owner) {
        return orderCommandUseCase.create(owner.getId(), List.of(
            new OrderLines.Line(airMax.getId(), 3),
            new OrderLines.Line(airForce.getId(), 2)
        )).id();
    }

    private PaymentInfo request(UserModel requester, Long orderId, String attemptKey) {
        return paymentRequestUseCase.request(new PaymentRequestCommand(requester.getId(), orderId, attemptKey, CARD_TYPE, CARD_NO));
    }

    /** PG 거래가 PENDING으로 기록될 때까지 기다린 뒤 fixture에서 결과를 정하고 reconcile(권위 조회)로 반영한다. */
    private PaymentInfo complete(UserModel requester, Long orderId, String pgStatus) {
        await().atMost(WAIT).until(() -> paymentJpaRepository.findByOrderId(orderId).orElseThrow().getStatus() == PaymentStatus.PENDING);
        String transactionKey = paymentJpaRepository.findByOrderId(orderId).orElseThrow().getTransactionKey();
        PG.complete(transactionKey, pgStatus, "SUCCESS".equals(pgStatus) ? null : "한도 초과");
        return paymentReconcileUseCase.reconcile(requester.getId(), orderId);
    }

    private PaymentInfo requestAndComplete(UserModel requester, Long orderId, String attemptKey, String pgStatus) {
        request(requester, orderId, attemptKey);
        return complete(requester, orderId, pgStatus);
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private List<Money> remainingOfGroups() {
        return pointGroupJpaRepository.findAll().stream().map(PointGroup::getRemaining).toList();
    }

    private List<PointHistory> useHistories() {
        return pointHistoryJpaRepository.findAll().stream()
            .filter(history -> history.getType() == PointHistoryType.USE)
            .toList();
    }

    private OrderModel orderOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private List<String> reservationRows(Long orderId) {
        return jdbcTemplate.queryForList(
            "SELECT CONCAT(status, ':', quantity) FROM stock_reservations WHERE order_id = ? ORDER BY product_id", String.class, orderId);
    }

    /** 확정이 거절됐을 때 요청 전과 같은지: 주문 DRAFT, 재고 10·10, 선점·결제 0, 포인트 그대로, PG 호출 0 (ORD-05, W5 4장 전체 rollback). */
    private void assertNothingChanged(Long orderId, Money remaining) {
        assertThat(orderOf(orderId).getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stockOf(airMax)).isEqualTo(10);
        assertThat(stockOf(airForce)).isEqualTo(10);
        assertThat(reservationRows(orderId)).isEmpty();
        assertThat(paymentJpaRepository.count()).isZero();
        assertThat(remainingOfGroups()).containsExactly(remaining);
        assertThat(useHistories()).isEmpty();
        assertThat(PG.createCalls()).isZero();
    }

    @DisplayName("확정할 수 있으면, ")
    @Nested
    class Success {

        // W5 이전: 포인트로 확정해 그룹의 남은 금액 3,000원·사용 이력 −7,000원이 함께 반영됐다.
        // 이제 결제는 PG라 포인트는 그대로(C-1)이고, 재고는 요청 때 선점(10→7, 10→8)했다가 PG 성공 확인 뒤 소비된다(C-5).
        @DisplayName("ORD-05·C-1·C-5 7,000원 주문의 결제를 요청하고 PG 성공을 확인하면 CONFIRMED·결제액 7,000원(PG)이 되고, 재고 선점이 소비되며 포인트 그룹·사용 이력은 그대로다.")
        @Test
        void confirmsAndAppliesAllChanges() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);

            // act
            PaymentInfo accepted = request(user, orderId, "attempt-1");
            int stockAfterRequest = stockOf(airMax);
            PaymentInfo confirmed = complete(user, orderId, "SUCCESS");

            // assert: 요청 시점 — PAYMENT_PENDING, 재고는 이미 선점됨
            assertThat(accepted.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stockAfterRequest).isEqualTo(7);

            // assert: PG 성공 확인 뒤
            assertThat(confirmed.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(confirmed.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
            OrderModel order = orderOf(orderId);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(Money.of(7_000));
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getConfirmedAt()).isNotNull();
            assertThat(stockOf(airMax)).isEqualTo(7);
            assertThat(stockOf(airForce)).isEqualTo(8);
            assertThat(reservationRows(orderId)).containsExactly("CONSUMED:3", "CONSUMED:2");
            assertThat(remainingOfGroups()).containsExactly(Money.of(10_000));
            assertThat(useHistories()).isEmpty();
            assertThat(PG.createCalls()).isEqualTo(1);
        }

        // W5 이전: 4,000원·5,000원 그룹에서 7,000원을 내면 쓴 그룹마다 사용 이력을 남겼다. 주문 확정은 이제 포인트를 쓰지 않으므로
        // "그룹별 사용 이력"은 확정에서 생기지 않는다 — 여러 그룹이 있어도 어느 그룹도 줄지 않고 사용 이력이 0인 것으로 옮긴다 (C-1).
        // 그룹별 사용 규칙 자체는 포인트 도메인(PointWalletPolicyTest)에 남아 있다.
        @DisplayName("ORD-05·C-1 4,000원 그룹과 5,000원 그룹이 있어도 7,000원 주문을 PG로 확정하면 어느 그룹도 쓰지 않고, 사용 이력을 남기지 않는다.")
        @Test
        void usesNoPointGroup() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 4_000);
            pointCommandUseCase.charge(user.getId(), 5_000);
            Long orderId = createOrderOf7000(user);

            // act
            requestAndComplete(user, orderId, "attempt-1", "SUCCESS");

            // assert
            assertThat(orderOf(orderId).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(useHistories()).isEmpty();
            assertThat(pointGroupJpaRepository.findAll())
                .extracting(PointGroup::getAmount, PointGroup::getRemaining)
                .containsExactlyInAnyOrder(
                    tuple(Money.of(4_000), Money.of(4_000)),
                    tuple(Money.of(5_000), Money.of(5_000))
                );
        }
    }

    @DisplayName("확정할 수 없으면, ")
    @Nested
    class Rejection {

        // W5 이전: 잔액 5,000원으로 7,000원 주문을 확정하면 409이고 아무것도 바뀌지 않았다. 이제 잔액은 확정에 쓰이지 않으므로 요청은 접수된다.
        // "결제할 수 없으면 주문·재고·포인트가 요청 전과 같다"는 의도는 PG가 결제를 거절(FAILED)한 경우로 옮긴다:
        // 주문 PAYMENT_FAILED, 선점은 한 번 반환(RELEASED)되어 재고 10·10, 포인트 그대로 (C-5).
        @DisplayName("ORD-04·C-5 잔액 5,000원으로 7,000원 주문을 요청해도 접수되고, PG가 거절로 끝나면 주문 PAYMENT_FAILED·재고 반환(10·10)이며 포인트 그룹·사용 이력이 그대로다.")
        @Test
        void rejectsWhenBalanceIsShort() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 5_000);
            Long orderId = createOrderOf7000(user);

            // act
            PaymentInfo failed = requestAndComplete(user, orderId, "attempt-1", "FAILED");

            // assert
            assertThat(failed.orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(failed.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(orderOf(orderId).getPaidAmount()).isNull();
            assertThat(stockOf(airMax)).isEqualTo(10);
            assertThat(stockOf(airForce)).isEqualTo(10);
            assertThat(reservationRows(orderId)).containsExactly("RELEASED:3", "RELEASED:2");
            assertThat(remainingOfGroups()).containsExactly(Money.of(5_000));
            assertThat(useHistories()).isEmpty();
        }

        @DisplayName("ORD-03·P-19 주문 뒤 가격이 1,000원에서 1,200원으로 바뀌면 409이고, 주문은 DRAFT로 남으며 재고 선점·결제 시도·PG 호출이 없고 포인트도 그대로다.")
        @Test
        void rejectsWhenPriceChanged() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);
            ProductModel changed = productJpaRepository.findById(airMax.getId()).orElseThrow();
            changed.update("에어맥스", 1_200);
            productJpaRepository.save(changed);

            // act & assert
            assertThatThrownBy(() -> request(user, orderId, "attempt-1"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertNothingChanged(orderId, Money.of(10_000));
        }

        @DisplayName("ORD-02 남의 주문을 확정하면 404이고, 아무것도 바뀌지 않는다.")
        @Test
        void hidesOthersOrder() {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            pointCommandUseCase.charge(user.getId(), 10_000);
            Long othersOrder = createOrderOf7000(other);

            // act & assert
            assertThatThrownBy(() -> request(user, othersOrder, "attempt-1"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
            assertNothingChanged(othersOrder, Money.of(10_000));
        }

        // W5: "재고와 포인트가 한 번만 빠져 있다" → 재고는 한 번만 선점·소비되고, 포인트는 한 번도 빠지지 않는다(C-1). 결제 시도·PG 생성도 1번.
        @DisplayName("ORD-02·P-17 이미 확정한 주문을 다른 시도 키로 다시 확정하면 409이고, 재고는 한 번만 빠져 있으며 포인트는 그대로·결제 시도와 PG 생성은 1번이다.")
        @Test
        void rejectsConfirmingTwice() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 20_000);
            Long orderId = createOrderOf7000(user);
            requestAndComplete(user, orderId, "attempt-1", "SUCCESS");

            // act & assert
            assertThatThrownBy(() -> request(user, orderId, "attempt-2"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stockOf(airMax)).isEqualTo(7);
            assertThat(stockOf(airForce)).isEqualTo(8);
            assertThat(remainingOfGroups()).containsExactly(Money.of(20_000));
            assertThat(useHistories()).isEmpty();
            assertThat(paymentJpaRepository.count()).isEqualTo(1);
            assertThat(PG.createCalls()).isEqualTo(1);
        }
    }

    @DisplayName("변경 도중 실패하면, ")
    @Nested
    class Rollback {

        // W5 이전: 재고·그룹·사용 이력 SQL이 나간 뒤 주문 저장에서 실패. 이제 TX A의 마지막 단계는 발송 장부(outbox) 저장이다 —
        // 그 앞의 재고 선점·주문 PAYMENT_PENDING·결제 시도 SQL이 실제로 나간 뒤 실패해도 전부 되돌려지고, commit되지 않았으니 PG 호출도 0이다.
        @DisplayName("ORD-05·W3 C-4 재고 선점·주문 상태·결제 시도의 SQL이 실제로 나간 뒤 장부 저장에서 실패하면, 새로 읽은 재고·선점·결제·주문 상태가 모두 요청 전이고 PG를 부르지 않는다.")
        @Test
        void rollsBackEverythingWhenLastStepFails() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);
            List<Object> sentBeforeFailure = new ArrayList<>();
            // 장부를 저장할 때 실패시킨다. 장부 저장이 선점·결제 저장보다 앞으로 옮겨지면 아래 "실패 직전" 값이 맞지 않아 테스트가 실패한다.
            doAnswer(invocation -> {
                // 같은 트랜잭션의 커넥션으로 읽으면 앞 단계의 UPDATE·INSERT가 이미 DB에 나가 있다 (아직 commit 전)
                sentBeforeFailure.add(jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, airMax.getId()));
                sentBeforeFailure.add(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_reservations WHERE status = 'RESERVED'", Long.class));
                sentBeforeFailure.add(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId));
                sentBeforeFailure.add(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class));
                throw new IllegalStateException("장부 저장 실패");
            }).when(paymentOutboxPort).save(any());

            // act
            assertThatThrownBy(() -> request(user, orderId, "attempt-1"))
                .isInstanceOf(IllegalStateException.class);

            // assert: 실패 직전에는 재고 10 → 7, 선점 2줄, 주문 PAYMENT_PENDING, 결제 시도 1줄이 DB에 반영돼 있었다
            assertThat(sentBeforeFailure).containsExactly(7, 2L, "PAYMENT_PENDING", 1L);
            // assert: 트랜잭션이 끝난 뒤 새로 읽으면 전부 요청 전이다
            assertNothingChanged(orderId, Money.of(10_000));
        }
    }
}
