package com.loopers.application.payment;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentFinalResult;
import com.loopers.domain.payment.PaymentResultService;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * reconcile(PaymentFacade.reconcile)의 업무 규칙을 실제 MySQL 과 가짜 PG 로 검증한다 (설계 5-6 결정 ⑳, 7-4, 7-6, 8-1 결정 ⑪).
 * HTTP 응답 코드는 PaymentV1ApiE2ETest 가 맡고, 여기서는 조회 결과별로 시도·주문·예약·재고·충돌 기록이 어떻게 되는지 본다.
 * "외부 성공 뒤 내부 저장 실패" 장면을 위해 ⑧ 예약 Repository 를 spy 로 둔다. 스텁은 테스트마다 자동으로 초기화된다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class PaymentReconcileIntegrationTest {

    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";

    @MockitoSpyBean
    private StockReservationRepository stockReservationRepository;

    private final PaymentFacade paymentFacade;
    private final OrderFacade orderFacade;
    private final PaymentResultService paymentResultService;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentLookupProperties lookupProperties;
    private final Clock clock;
    private final OrderConfirmedPublisher orderConfirmedPublisher;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public PaymentReconcileIntegrationTest(
        PaymentFacade paymentFacade,
        OrderFacade orderFacade,
        PaymentResultService paymentResultService,
        FakePaymentGateway fakePaymentGateway,
        UserRepository userRepository,
        OrderService orderService,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentLookupProperties lookupProperties,
        Clock clock,
        OrderConfirmedPublisher orderConfirmedPublisher,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        OrderJpaRepository orderJpaRepository,
        PaymentAttemptJpaRepository paymentAttemptJpaRepository,
        StockReservationJpaRepository stockReservationJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentFacade = paymentFacade;
        this.orderFacade = orderFacade;
        this.paymentResultService = paymentResultService;
        this.fakePaymentGateway = fakePaymentGateway;
        this.userRepository = userRepository;
        this.orderService = orderService;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.lookupProperties = lookupProperties;
        this.clock = clock;
        this.orderConfirmedPublisher = orderConfirmedPublisher;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.paymentAttemptJpaRepository = paymentAttemptJpaRepository;
        this.stockReservationJpaRepository = stockReservationJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("UNKNOWN 시도를 reconcile 할 때, ")
    @Nested
    class FromUnknown {
        @DisplayName("조회가 SUCCESS 1건이면, 시도 SUCCESS + key 저장·주문 CONFIRMED·예약 CONSUMED·재고 3 이고 create 는 추가 0회다.")
        @Test
        void confirms_whenLookupFindsOneSuccess() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPaymentWithLostResponse(userId, orderId, "k1");
            String transactionKey = onlyTransactionOf(attempt.getProviderOrderId()).transactionKey();
            fakePaymentGateway.markSuccess(transactionKey);

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isTrue();
            assertThat(info.attemptStatus()).isEqualTo("SUCCESS");
            assertThat(info.lookupUnavailable()).isFalse();
            PaymentAttempt settled = attemptOf(orderId);
            assertThat(settled.getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
            assertThat(settled.getTransactionKey()).isEqualTo(transactionKey);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(orderOf(orderId).getPaidAmount()).isEqualTo(2000L);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.CONSUMED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        @DisplayName("조회가 PENDING 1건이면, 시도는 PENDING 으로 바뀌고 key 가 저장되며 주문·예약·재고는 그대로다 (결정 ⑪).")
        @Test
        void marksPendingWithKey_whenLookupFindsOnePending() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPaymentWithLostResponse(userId, orderId, "k1");
            String transactionKey = onlyTransactionOf(attempt.getProviderOrderId()).transactionKey();

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.attemptStatus()).isEqualTo("PENDING");
            assertThat(info.lookupUnavailable()).isFalse();
            PaymentAttempt pending = attemptOf(orderId);
            assertThat(pending.getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(pending.getTransactionKey()).isEqualTo(transactionKey);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
        }
    }

    @DisplayName("조회 결과가 정상 1건 일치가 아닐 때, ")
    @Nested
    class AbnormalLookup {
        @DisplayName("0건이면, 충돌이 아니라 단순 미확인이다. 시도·주문·예약·재고 그대로, lookupUnavailable=false.")
        @Test
        void keepsEverythingWithoutConflict_whenLookupIsEmpty() {
            // arrange - create 가 PG 에 닿지 않은 채 REQUESTED 로 남은 시도 (3장 "호출 전 사망")
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 3);
            Long orderId = saveAttemptFixture(userId, productId, 2, "k1", PaymentAttempt.Status.REQUESTED, null);

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.attemptStatus()).isEqualTo("REQUESTED");
            assertThat(info.lookupUnavailable()).isFalse();
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
            assertUnchanged(orderId, productId, PaymentAttempt.Status.REQUESTED);
            assertThat(attemptOf(orderId).isConflicted()).isFalse();
        }

        @DisplayName("같은 주문에 거래가 2건 이상이면, 충돌(MULTIPLE_TRANSACTIONS)을 기록하고 상태는 그대로 두며 lookupUnavailable=true 다.")
        @Test
        void recordsConflict_whenLookupFindsMultipleTransactions() {
            // arrange - 숨은 재시도로 같은 주문에 거래가 하나 더 생긴 상황
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.create(new PaymentCreateCommand(attempt.getProviderOrderId(), CARD_TYPE, CARD_NO, 2000L));
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.lookupUnavailable()).isTrue();
            assertUnchangedWithConflict(orderId, productId, PaymentAttempt.Status.PENDING, PaymentAttempt.ConflictReason.MULTIPLE_TRANSACTIONS);
        }

        @DisplayName("금액이 다르면, PG 가 SUCCESS 라도 확정하지 않고 충돌(AMOUNT_MISMATCH)을 기록하며 lookupUnavailable=true 다.")
        @Test
        void recordsConflict_whenAmountDiffers() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            jdbcTemplate.update("UPDATE payment_attempts SET amount = 999 WHERE id = ?", attempt.getId());
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.lookupUnavailable()).isTrue();
            assertUnchangedWithConflict(orderId, productId, PaymentAttempt.Status.PENDING, PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);
        }

        @DisplayName("저장된 transactionKey 가 목록에 없으면, 충돌(TRANSACTION_KEY_MISSING)을 기록하고 상태는 그대로 두며 lookupUnavailable=true 다.")
        @Test
        void recordsConflict_whenStoredTransactionKeyIsMissing() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            jdbcTemplate.update("UPDATE payment_attempts SET transaction_key = 'FAKE-TX-777777' WHERE id = ?", attempt.getId());
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.lookupUnavailable()).isTrue();
            assertUnchangedWithConflict(orderId, productId, PaymentAttempt.Status.PENDING, PaymentAttempt.ConflictReason.TRANSACTION_KEY_MISSING);
            assertThat(attemptOf(orderId).getTransactionKey()).isEqualTo("FAKE-TX-777777");
        }

        @DisplayName("PG 조회가 실패하면, lookupUnavailable=true 와 저장 상태를 돌려주고 시도·주문·예약·재고는 불변이며 충돌도 기록하지 않는다.")
        @Test
        void returnsUnavailable_andKeepsEverything_whenLookupFails() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            fakePaymentGateway.willFailLookups();

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.attemptStatus()).isEqualTo("PENDING");
            assertThat(info.lookupUnavailable()).isTrue();
            assertUnchanged(orderId, productId, PaymentAttempt.Status.PENDING);
            assertThat(attemptOf(orderId).getTransactionKey()).isEqualTo(attempt.getTransactionKey());
            assertThat(attemptOf(orderId).isConflicted()).isFalse();
        }

        @DisplayName("서킷이 차단 중이면, 조회 실패와 같이 lookupUnavailable=true 와 저장 상태를 돌려주고 시도·주문·예약·재고는 불변이며 충돌도 기록하지 않는다 (설계 5-7 결정 ㉙).")
        @Test
        void returnsUnavailable_andKeepsEverything_whenCircuitIsOpen() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            fakePaymentGateway.willOpenCircuit();

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.attemptStatus()).isEqualTo("PENDING");
            assertThat(info.lookupUnavailable()).isTrue();
            assertUnchanged(orderId, productId, PaymentAttempt.Status.PENDING);
            assertThat(attemptOf(orderId).isConflicted()).isFalse();
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
        }
    }

    @DisplayName("종결·복구·재시작 장면에서, ")
    @Nested
    class Recovery {
        @DisplayName("서킷 차단이 풀리면, 다음 reconcile 이 같은 외부 결과를 조회해 반영하고 create 는 추가 0회다.")
        @Test
        void appliesResult_afterCircuitRecovers() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            fakePaymentGateway.willOpenCircuit();

            // act 1 - 차단 중
            OrderConfirmInfo blocked = paymentFacade.reconcile(userId, orderId);

            // assert 1
            assertThat(blocked.lookupUnavailable()).isTrue();
            assertUnchanged(orderId, productId, PaymentAttempt.Status.PENDING);

            // act 2 - 회복 뒤
            fakePaymentGateway.willServeLookups();
            OrderConfirmInfo recovered = paymentFacade.reconcile(userId, orderId);

            // assert 2
            assertThat(recovered.settled()).isTrue();
            assertThat(recovered.attemptStatus()).isEqualTo("SUCCESS");
            assertThat(recovered.lookupUnavailable()).isFalse();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.CONSUMED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(2);
        }

        @DisplayName("이미 종결된 시도면, PG 조회 0회로 저장 결과(settled=true)를 돌려준다.")
        @Test
        void returnsStoredResultWithoutLookup_whenAlreadySettled() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            paymentResultService.applyFinal(attempt.getId(), PaymentFinalResult.success(attempt.getTransactionKey()));

            // act
            OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isTrue();
            assertThat(info.attemptStatus()).isEqualTo("SUCCESS");
            assertThat(info.lookupUnavailable()).isFalse();
            assertThat(fakePaymentGateway.lookupCallCount()).isZero();
        }

        @DisplayName("PG SUCCESS 를 반영하는 TX B 가 실패하면 전부 rollback 되고, reconcile 을 다시 부르면 CONFIRMED 가 1회 되며 create 는 추가 0회다.")
        @Test
        void confirmsOnceOnRetry_whenInternalSaveFailsAfterPgSuccess() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            doThrow(new IllegalStateException("테스트: 예약 갱신 실패"))
                .doCallRealMethod()
                .when(stockReservationRepository).consumeIfReserved(anyLong());

            // act 1 - 첫 reconcile 은 ⑧ 에서 실패한다
            assertThrows(IllegalStateException.class, () -> paymentFacade.reconcile(userId, orderId));

            // assert 1 - 전부 rollback
            assertUnchanged(orderId, productId, PaymentAttempt.Status.PENDING);

            // act 2 - 같은 외부 결과를 다시 조회해 적용
            OrderConfirmInfo retried = paymentFacade.reconcile(userId, orderId);

            // assert 2
            assertThat(retried.settled()).isTrue();
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.CONSUMED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        @DisplayName("발송 기록 뒤 재시작: REQUESTED 로 남은 시도를 서비스 재생성 후 reconcile 하면, 조회만 하고 create 는 0회이며 거래가 PENDING 이면 key 만 저장된다.")
        @Test
        void looksUpWithoutResend_afterRestartWithRequestedAttempt() {
            // arrange - TX A 커밋 뒤 create 는 나갔지만 응답을 받기 전에 프로세스가 죽은 상황. 같은 DB·같은 가짜 PG 상태를 유지한다.
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 3);
            Long orderId = saveAttemptFixture(userId, productId, 2, "k1", PaymentAttempt.Status.REQUESTED, null);
            String providerOrderId = PaymentAttempt.providerOrderIdOf(orderId);
            fakePaymentGateway.create(new PaymentCreateCommand(providerOrderId, CARD_TYPE, CARD_NO, 2000L));
            int createsBeforeRestart = fakePaymentGateway.createCount();
            PaymentFacade restarted = new PaymentFacade(
                userRepository, orderService, paymentAttemptRepository, paymentResultService, fakePaymentGateway, lookupProperties, clock,
                orderConfirmedPublisher);

            // act
            OrderConfirmInfo info = restarted.reconcile(userId, orderId);

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.attemptStatus()).isEqualTo("PENDING");
            assertThat(fakePaymentGateway.createCount()).isEqualTo(createsBeforeRestart);
            assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo(onlyTransactionOf(providerOrderId).transactionKey());
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
        }
    }

    private void assertUnchanged(Long orderId, Long productId, PaymentAttempt.Status expectedStatus) {
        assertThat(attemptOf(orderId).getStatus()).isEqualTo(expectedStatus);
        assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
        assertThat(stockOf(productId)).isEqualTo(3);
    }

    private void assertUnchangedWithConflict(
        Long orderId, Long productId, PaymentAttempt.Status expectedStatus, PaymentAttempt.ConflictReason expectedReason
    ) {
        assertUnchanged(orderId, productId, expectedStatus);
        PaymentAttempt attempt = attemptOf(orderId);
        assertThat(attempt.isConflicted()).isTrue();
        assertThat(attempt.getConflictReason()).isEqualTo(expectedReason);
        assertThat(attempt.getConflictedAt()).isNotNull();
    }

    private Long saveUser() {
        return userJpaRepository.save(new User()).getId();
    }

    private Long saveProduct(long price, int stock) {
        Product product = new Product(1L, "상품", new Price(price));
        product.changeStock(stock);
        return productJpaRepository.save(product).getId();
    }

    private Long createOrder(Long userId, Long productId, int quantity) {
        return orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productId, quantity))).orderId();
    }

    /**
     * confirm 으로 PENDING + transactionKey 상태의 시도를 만든다.
     */
    private PaymentAttempt startPayment(Long userId, Long orderId, String attemptKey) {
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        return attemptOf(orderId);
    }

    /**
     * create 응답이 유실된 confirm. 시도는 UNKNOWN(key 없음)이고 가짜 PG 에는 PENDING 거래 1건이 있다.
     */
    private PaymentAttempt startPaymentWithLostResponse(Long userId, Long orderId, String attemptKey) {
        fakePaymentGateway.willCreateButLoseResponse();
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        fakePaymentGateway.willAcceptPending();
        PaymentAttempt attempt = attemptOf(orderId);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.UNKNOWN);
        assertThat(attempt.getTransactionKey()).isNull();
        return attempt;
    }

    /**
     * PG 를 거치지 않고 "TX A 가 끝난 뒤" 상태를 직접 만든다. 상품 재고는 이미 차감된 값으로 저장해 둔다.
     */
    private Long saveAttemptFixture(
        Long userId, Long productId, int quantity, String attemptKey, PaymentAttempt.Status status, String transactionKey
    ) {
        long unitPrice = productJpaRepository.findById(productId).orElseThrow().getPrice().getAmount();
        Long orderId = orderJpaRepository.save(new Order(userId, List.of(new OrderItem(productId, quantity, unitPrice)))).getId();
        jdbcTemplate.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG' WHERE id = ?", orderId);
        stockReservationJpaRepository.save(new StockReservation(orderId, productId, quantity));
        Long attemptId = paymentAttemptJpaRepository.save(new PaymentAttempt(
            orderId, userId, attemptKey, PaymentAttempt.providerOrderIdOf(orderId), unitPrice * quantity, CARD_TYPE)).getId();
        jdbcTemplate.update("UPDATE payment_attempts SET status = ?, transaction_key = ? WHERE id = ?",
            status.name(), transactionKey, attemptId);
        return orderId;
    }

    private PaymentTransaction onlyTransactionOf(String providerOrderId) {
        List<PaymentTransaction> transactions = fakePaymentGateway.transactions().stream()
            .filter(transaction -> transaction.providerOrderId().equals(providerOrderId))
            .toList();
        assertThat(transactions).hasSize(1);
        return transactions.get(0);
    }

    private Order orderOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private PaymentAttempt attemptOf(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private List<StockReservation.Status> reservationStatusesOf(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId).stream().map(StockReservation::getStatus).toList();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }
}
