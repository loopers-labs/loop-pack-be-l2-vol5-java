package com.loopers.application.payment;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentFinalResult;
import com.loopers.domain.payment.PaymentResultService;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 스케줄러 1회 실행(PaymentFacade.runScheduledLookups)을 실제 MySQL 과 가짜 PG 로 검증한다 (설계 5-3 결정 ⑫).
 * @Scheduled 자동 실행은 test 프로필에서 꺼져 있고(enabled=false), 여기서는 메서드를 직접 호출한다.
 * 시각은 Clock 빈을 mock 으로 바꿔 움직인다. sleep 은 쓰지 않는다.
 * test 프로필 설정: min-age 0, 간격 0, multiplier 1, max-lookups 2. 다른 값이 필요한 장면은 Facade 를 직접 만들어 설정을 덮어쓴다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class PaymentScheduledLookupIntegrationTest {

    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @MockitoBean
    private Clock clock;

    private final PaymentFacade paymentFacade;
    private final OrderFacade orderFacade;
    private final PaymentResultService paymentResultService;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentLookupProperties lookupProperties;
    private final OrderConfirmedPublisher orderConfirmedPublisher;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Instant now;

    @Autowired
    public PaymentScheduledLookupIntegrationTest(
        PaymentFacade paymentFacade,
        OrderFacade orderFacade,
        PaymentResultService paymentResultService,
        FakePaymentGateway fakePaymentGateway,
        UserRepository userRepository,
        OrderService orderService,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentLookupProperties lookupProperties,
        OrderConfirmedPublisher orderConfirmedPublisher,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        OrderJpaRepository orderJpaRepository,
        PaymentAttemptJpaRepository paymentAttemptJpaRepository,
        StockReservationJpaRepository stockReservationJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderConfirmedPublisher = orderConfirmedPublisher;
        this.paymentFacade = paymentFacade;
        this.orderFacade = orderFacade;
        this.paymentResultService = paymentResultService;
        this.fakePaymentGateway = fakePaymentGateway;
        this.userRepository = userRepository;
        this.orderService = orderService;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.lookupProperties = lookupProperties;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.paymentAttemptJpaRepository = paymentAttemptJpaRepository;
        this.stockReservationJpaRepository = stockReservationJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    /**
     * 고정 Clock 의 now 는 실제 시각 + 1분이다. 시도의 updatedAt 은 실제 시각이라 fixture 가 now 보다 뒤에 만들어지면
     * min-age 0 에서도 "마지막 변경 후 0초 경과" 조건에 걸리지 않는다. 1분 뒤로 두면 그 뒤에 만든 fixture 도 대상이 된다.
     */
    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(1));
        setClock(now);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("미종결이고 최소 경과 시간(60초)이 지난 시도만 조회한다. 종결된 시도, 막 생성된 시도, 수동 확인 대상은 건너뛴다.")
    @Test
    void looksUpOnlyOpenAttemptsOlderThanMinAge() {
        // arrange
        PaymentFacade facade = facadeWith(Duration.ofSeconds(60), Duration.ZERO, 1, Duration.ZERO, 10);
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 9);
        Long openOrderId = createOrder(userId, productId, 1);
        startPaymentWithLostResponse(userId, openOrderId, "open");
        Long settledOrderId = createOrder(userId, productId, 1);
        PaymentAttempt settled = startPayment(userId, settledOrderId, "settled");
        fakePaymentGateway.markSuccess(settled.getTransactionKey());
        paymentResultService.applyFinal(settled.getId(), PaymentFinalResult.success(settled.getTransactionKey()));
        Long manualOrderId = saveAttemptFixture(userId, productId, 1, "manual", PaymentAttempt.Status.UNKNOWN);
        jdbcTemplate.update("UPDATE payment_attempts SET manual_check_required = true WHERE order_id = ?", manualOrderId);
        int base = fakePaymentGateway.lookupCallCount();

        // act 1 - 지금: 미종결 시도도 아직 60초가 안 지났다
        facade.runScheduledLookups();

        // assert 1
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(base);

        // act 2 - 2분 뒤
        setClock(now.plus(Duration.ofMinutes(2)));
        facade.runScheduledLookups();

        // assert 2 - 미종결 1건만 조회됐다. 가짜 PG 에 그 주문의 PENDING 거래 1건이 있어 UNKNOWN → PENDING 경로를 탄다
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(base + 1);
        assertThat(attemptOf(openOrderId).getLookupCount()).isEqualTo(1);
        assertThat(attemptOf(openOrderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
        assertThat(attemptOf(settledOrderId).getLookupCount()).isZero();
        assertThat(attemptOf(manualOrderId).getLookupCount()).isZero();
    }

    @DisplayName("조회할 때마다 조회 횟수가 1 늘고 다음 조회 시각이 갱신된다. 조회 실패(UNAVAILABLE)도 실제로 물어본 것이므로 센다.")
    @Test
    void recordsLookupCountAndNextLookupAt_onEveryLookup() {
        // arrange - 간격 0·한도 10 이라 매 실행이 조회 대상이다
        PaymentFacade facade = facadeWith(Duration.ZERO, Duration.ZERO, 1, Duration.ZERO, 10);
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 5);
        Long orderId = createOrder(userId, productId, 2);
        startPaymentWithLostResponse(userId, orderId, "k1");
        int base = fakePaymentGateway.lookupCallCount();

        // act 1
        facade.runScheduledLookups();

        // assert 1
        PaymentAttempt afterFirst = attemptOf(orderId);
        assertThat(afterFirst.getLookupCount()).isEqualTo(1);
        assertThat(afterFirst.getNextLookupAt()).isNotNull();
        assertThat(afterFirst.getNextLookupAt().toInstant()).isEqualTo(now);
        assertThat(afterFirst.getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);

        // act 2 - 조회 실패도 1회로 센다
        fakePaymentGateway.willFailLookups();
        Instant later = now.plus(Duration.ofMinutes(3));
        setClock(later);
        facade.runScheduledLookups();

        // assert 2
        PaymentAttempt afterSecond = attemptOf(orderId);
        assertThat(afterSecond.getLookupCount()).isEqualTo(2);
        assertThat(afterSecond.getNextLookupAt().toInstant()).isEqualTo(later);
        assertThat(afterSecond.getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(base + 2);
    }

    @DisplayName("간격 증가: 첫 간격 1분·배수 2·상한 4분이면, 다음 조회까지의 간격이 1분 → 2분 → 4분 → 4분(상한)으로 늘어난다.")
    @Test
    void growsIntervalUpToCap() {
        // arrange
        PaymentFacade facade = facadeWith(Duration.ZERO, Duration.ofMinutes(1), 2, Duration.ofMinutes(4), 10);
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 5);
        Long orderId = createOrder(userId, productId, 2);
        startPaymentWithLostResponse(userId, orderId, "k1");

        // act & assert - 매번 nextLookupAt 이 되는 시각으로 Clock 을 옮겨 실행한다
        Instant t = now;
        Duration[] expectedIntervals = {
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(4)
        };
        for (int i = 0; i < expectedIntervals.length; i++) {
            setClock(t);
            facade.runScheduledLookups();

            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getLookupCount()).isEqualTo(i + 1);
            assertThat(Duration.between(t, attempt.getNextLookupAt().toInstant()))
                .as("%d번째 조회 뒤 간격", i + 1)
                .isEqualTo(expectedIntervals[i]);
            t = attempt.getNextLookupAt().toInstant();
        }
    }

    @DisplayName("다음 조회 시각 전에는 다시 조회하지 않고, 한도(test 설정 2회)에 도달하면 수동 확인 필요로 표시한 뒤 대상에서 빠진다. 시도·주문·예약은 그대로다.")
    @Test
    void marksManualCheckRequired_andStopsLookingUp_whenLimitIsReached() {
        // arrange - test 프로필: min-age 0, 간격 0, max-lookups 2
        assertThat(lookupProperties.maxLookups()).isEqualTo(2);
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 5);
        Long orderId = createOrder(userId, productId, 2);
        startPaymentWithLostResponse(userId, orderId, "k1");
        int base = fakePaymentGateway.lookupCallCount();

        // act 1 - 두 번 실행하면 한도에 닿는다
        paymentFacade.runScheduledLookups();
        paymentFacade.runScheduledLookups();

        // assert 1
        PaymentAttempt limited = attemptOf(orderId);
        assertThat(limited.getLookupCount()).isEqualTo(2);
        assertThat(limited.isManualCheckRequired()).isTrue();
        assertThat(limited.getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
        assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(base + 2);

        // act 2 - 더 실행해도 대상이 아니다
        paymentFacade.runScheduledLookups();

        // assert 2
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(base + 2);
        assertThat(attemptOf(orderId).getLookupCount()).isEqualTo(2);
    }

    @DisplayName("한 건의 조회에서 예외가 나도 다음 건은 처리된다.")
    @Test
    void continuesWithNextAttempt_whenOneLookupThrows() {
        // arrange
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 9);
        Long failingOrderId = createOrder(userId, productId, 1);
        PaymentAttempt failing = startPaymentWithLostResponse(userId, failingOrderId, "k1");
        Long healthyOrderId = createOrder(userId, productId, 1);
        startPaymentWithLostResponse(userId, healthyOrderId, "k2");
        fakePaymentGateway.willThrowOnLookupOf(failing.getProviderOrderId());

        // act
        paymentFacade.runScheduledLookups();

        // assert - 둘 다 조회를 시도했고, 정상 건은 기록됐다. 예외가 난 건도 실제로 물어봤으므로 1회로 센다
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(2);
        assertThat(attemptOf(failingOrderId).getLookupCount()).isEqualTo(1);
        assertThat(attemptOf(healthyOrderId).getLookupCount()).isEqualTo(1);
        assertThat(attemptOf(healthyOrderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
    }

    @DisplayName("사용자 reconcile 은 조회 횟수에 포함되지 않고 다음 조회 시각도 건드리지 않는다.")
    @Test
    void userReconcileDoesNotCountTowardLookupLimit() {
        // arrange
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 5);
        Long orderId = createOrder(userId, productId, 2);
        startPayment(userId, orderId, "k1");

        // act
        paymentFacade.reconcile(userId, orderId);
        paymentFacade.reconcile(userId, orderId);
        paymentFacade.reconcile(userId, orderId);

        // assert
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(3);
        PaymentAttempt attempt = attemptOf(orderId);
        assertThat(attempt.getLookupCount()).isZero();
        assertThat(attempt.getNextLookupAt()).isNull();
        assertThat(attempt.isManualCheckRequired()).isFalse();
    }

    @DisplayName("서킷이 차단 중이면, 첫 건에서 그 주기를 중단한다. 이후 시도는 조회하지 않고 어느 시도의 조회 횟수·다음 조회 시각도 바뀌지 않는다 (설계 5-7 결정 ㉙).")
    @Test
    void stopsCycleWithoutCounting_whenCircuitIsOpen() {
        // arrange - 대상 2건
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 9);
        Long firstOrderId = createOrder(userId, productId, 1);
        startPaymentWithLostResponse(userId, firstOrderId, "k1");
        Long secondOrderId = createOrder(userId, productId, 1);
        startPaymentWithLostResponse(userId, secondOrderId, "k2");
        fakePaymentGateway.reset();
        fakePaymentGateway.willOpenCircuit();

        // act
        paymentFacade.runScheduledLookups();

        // assert - 첫 건만 가짜 PG 에 닿았고(차단 응답), 둘 다 조회 기록이 없다
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(1);
        for (Long orderId : List.of(firstOrderId, secondOrderId)) {
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getLookupCount()).isZero();
            assertThat(attempt.getNextLookupAt()).isNull();
            assertThat(attempt.isManualCheckRequired()).isFalse();
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.UNKNOWN);
        }
    }

    @DisplayName("일반 조회 실패(UNAVAILABLE)는 차단과 달리 건마다 실제로 물어본 것이므로, 모든 대상의 조회 횟수가 1 늘고 다음 조회 시각이 찍힌다.")
    @Test
    void countsEveryAttempt_whenLookupFailsNormally() {
        // arrange - 대상 2건
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 9);
        Long firstOrderId = createOrder(userId, productId, 1);
        startPaymentWithLostResponse(userId, firstOrderId, "k1");
        Long secondOrderId = createOrder(userId, productId, 1);
        startPaymentWithLostResponse(userId, secondOrderId, "k2");
        fakePaymentGateway.reset();
        fakePaymentGateway.willFailLookups();

        // act
        paymentFacade.runScheduledLookups();

        // assert
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(2);
        for (Long orderId : List.of(firstOrderId, secondOrderId)) {
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getLookupCount()).isEqualTo(1);
            assertThat(attempt.getNextLookupAt().toInstant()).isEqualTo(now);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.UNKNOWN);
        }
    }

    @DisplayName("스케줄러 1회 실행은 PG 를 조회하는 동안 DB 트랜잭션을 보유하지 않는다.")
    @Test
    void holdsNoTransactionWhileCallingPg() {
        // arrange
        Long userId = saveUser();
        Long productId = saveProduct(1000L, 5);
        Long orderId = createOrder(userId, productId, 2);
        startPaymentWithLostResponse(userId, orderId, "k1");
        int base = fakePaymentGateway.lookupCallCount();

        // act
        paymentFacade.runScheduledLookups();

        // assert - 거래 1건이 있는 상태에서 조회·반영(UNKNOWN → PENDING)까지 했고, 그동안 TX 를 보유하지 않았다
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(base + 1);
        assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
        assertThat(fakePaymentGateway.lookupSeenInsideTransaction()).isFalse();
    }

    private void setClock(Instant instant) {
        given(clock.instant()).willReturn(instant);
        given(clock.getZone()).willReturn(ZONE);
    }

    /**
     * 설정만 바꾼 Facade. 나머지 협력자는 컨텍스트의 빈(mock Clock 포함)을 그대로 쓴다.
     */
    private PaymentFacade facadeWith(Duration minAge, Duration initialInterval, int multiplier, Duration maxInterval, int maxLookups) {
        PaymentLookupProperties properties = new PaymentLookupProperties(
            false, Duration.ofSeconds(30), minAge, initialInterval, multiplier, maxInterval, maxLookups);
        return new PaymentFacade(
            userRepository, orderService, paymentAttemptRepository, paymentResultService, fakePaymentGateway, properties, clock,
            orderConfirmedPublisher);
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

    private PaymentAttempt startPayment(Long userId, Long orderId, String attemptKey) {
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        return attemptOf(orderId);
    }

    /**
     * create 응답이 유실된 confirm. 시도는 UNKNOWN(key 없음)이고 가짜 PG 에는 PENDING 거래 1건이 있어 미종결로 남는다.
     */
    private PaymentAttempt startPaymentWithLostResponse(Long userId, Long orderId, String attemptKey) {
        fakePaymentGateway.willCreateButLoseResponse();
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        fakePaymentGateway.willAcceptPending();
        return attemptOf(orderId);
    }

    /**
     * PG 를 거치지 않고 "TX A 가 끝난 뒤" 상태를 직접 만든다. 재고는 이미 차감된 값으로 저장해 둔다.
     */
    private Long saveAttemptFixture(Long userId, Long productId, int quantity, String attemptKey, PaymentAttempt.Status status) {
        long unitPrice = productJpaRepository.findById(productId).orElseThrow().getPrice().getAmount();
        Long orderId = orderJpaRepository.save(new Order(userId, List.of(new OrderItem(productId, quantity, unitPrice)))).getId();
        jdbcTemplate.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG' WHERE id = ?", orderId);
        stockReservationJpaRepository.save(new StockReservation(orderId, productId, quantity));
        Long attemptId = paymentAttemptJpaRepository.save(new PaymentAttempt(
            orderId, userId, attemptKey, PaymentAttempt.providerOrderIdOf(orderId), unitPrice * quantity, CARD_TYPE)).getId();
        jdbcTemplate.update("UPDATE payment_attempts SET status = ? WHERE id = ?", status.name(), attemptId);
        return orderId;
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
