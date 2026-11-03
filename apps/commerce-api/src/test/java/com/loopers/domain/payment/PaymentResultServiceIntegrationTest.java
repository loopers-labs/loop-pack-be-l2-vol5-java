package com.loopers.domain.payment;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.HttpPaymentGateway;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.ConcurrentRunner;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

/**
 * TX B(PaymentResultService)가 같은 결과를 한 번만 반영하는지 실제 MySQL 로 검증한다.
 * TX A 를 거치지 않고 "재고 5 에서 2 를 확보한 뒤, PG 가 PENDING 으로 수락한" 상태를 DB 에 직접 심는다.
 * 이 클래스는 가짜 PG(FakePaymentGatewayConfig)를 @Import 하지 않는다. 운영 PG 빈이 그대로 뜨는 컨텍스트라
 * "가짜 없이 confirm 하면 크게 실패한다" 장면(WithoutFakeGateway)도 여기서 본다.
 */
@SpringBootTest
class PaymentResultServiceIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TRANSACTION_KEY = "FAKE-TX-000001";
    /** 고정 Clock 의 시각은 나노초까지 있고, 저장되는 확정 시각은 밀리초로 잘린 값이어야 한다 (6주차 설계 D2). */
    private static final Instant NOW = Instant.parse("2026-09-07T01:00:00.123456789Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123Z");

    @MockitoBean
    private Clock clock;

    private final PaymentResultService paymentResultService;
    private final OrderFacade orderFacade;
    private final PaymentGateway paymentGateway;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public PaymentResultServiceIntegrationTest(
        PaymentResultService paymentResultService,
        OrderFacade orderFacade,
        PaymentGateway paymentGateway,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        OrderJpaRepository orderJpaRepository,
        PaymentAttemptJpaRepository paymentAttemptJpaRepository,
        StockReservationJpaRepository stockReservationJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp,
        PlatformTransactionManager transactionManager
    ) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.paymentResultService = paymentResultService;
        this.orderFacade = orderFacade;
        this.paymentGateway = paymentGateway;
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
        given(clock.instant()).willReturn(NOW);
        given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /**
     * 확보 뒤 상태. 재고 3 = 5 - 2, 주문 PAYMENT_PENDING, 예약 RESERVED 2개, 시도 PENDING + key.
     */
    record Seed(Long userId, Long productId, Long orderId, Long attemptId, Long reservationId) {}

    @DisplayName("같은 결과를 거듭 적용할 때, ")
    @Nested
    class DuplicateApply {
        @DisplayName("같은 FAILED 결과를 두 번 적용하면, 두 번째는 효과가 없어 재고는 5 (7 아님)이고 예약 반환은 1회다.")
        @Test
        void releasesOnlyOnce_whenSameFailedResultIsAppliedTwice() {
            // arrange
            Seed seed = seedReservedOrder();
            PaymentFinalResult failed = PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY);

            // act
            PaymentApplyResult first = paymentResultService.applyFinal(seed.attemptId(), failed);
            PaymentApplyResult second = paymentResultService.applyFinal(seed.attemptId(), failed);

            // assert
            assertThat(first.applied()).isTrue();
            assertThat(first.firstConfirmation()).isNull();
            assertThat(second.applied()).isFalse();
            assertThat(second.firstConfirmation()).isNull();
            assertThat(stockOf(seed.productId())).isEqualTo(5);
            assertThat(reservationOf(seed).getStatus()).isEqualTo(StockReservation.Status.RELEASED);
            PaymentAttempt attempt = attemptOf(seed);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.FAILED);
            assertThat(attempt.getFailureReason()).isEqualTo(PaymentAttempt.FailureReason.PG_FAILED);
            Order order = orderOf(seed);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(order.getPaidAmount()).isZero();
            assertThat(order.getConfirmedAt()).isNull();
            assertThat(order.getUpdatedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        }

        @DisplayName("같은 FAILED 결과를 8개 스레드가 동시에 적용하면, 정확히 하나만 반영되고 재고는 5 다.")
        @Test
        void appliesOnlyOnce_whenSameResultIsAppliedConcurrently() throws InterruptedException {
            // arrange
            Seed seed = seedReservedOrder();
            PaymentFinalResult failed = PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY);
            List<Callable<PaymentApplyResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> paymentResultService.applyFinal(seed.attemptId(), failed));
            }

            // act
            List<ConcurrentRunner.Run<PaymentApplyResult>> runs = ConcurrentRunner.runAll(tasks, TIMEOUT);

            // assert
            List<Throwable> errors = runs.stream().filter(ConcurrentRunner.Run::failed).map(ConcurrentRunner.Run::error).toList();
            assertThat(errors).withFailMessage("오류 %d건: %s", errors.size(), errors).isEmpty();
            long applied = runs.stream().filter(run -> run.value() != null && run.value().applied()).count();
            assertThat(applied).isEqualTo(1);
            assertThat(stockOf(seed.productId())).isEqualTo(5);
            assertThat(reservationOf(seed).getStatus()).isEqualTo(StockReservation.Status.RELEASED);
            assertThat(attemptOf(seed).getStatus()).isEqualTo(PaymentAttempt.Status.FAILED);
        }
    }

    @DisplayName("이미 종결된 시도에 다른 결과가 오면, ")
    @Nested
    class AfterSettlement {
        @DisplayName("SUCCESS 로 종결된 시도에 FAILED 를 적용해도, 아무것도 바뀌지 않는다 (덮어쓰지 않음). confirmedAt 은 고정 Clock 의 밀리초 절단값으로 한 번만 기록된다.")
        @Test
        void doesNotOverwrite_whenFailedArrivesAfterSuccess() {
            // arrange
            Seed seed = seedReservedOrder();
            PaymentApplyResult first = paymentResultService.applyFinal(seed.attemptId(), PaymentFinalResult.success(TRANSACTION_KEY));
            assertThat(first.applied()).isTrue();
            assertThat(orderOf(seed).getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
            // 1회차 payload: confirmedAt 은 DB 에 저장된 값과 같고, 품목은 저장된 품목(상품 1개 × 2)이다
            OrderConfirmedEvent payload = first.firstConfirmation();
            assertThat(payload).isNotNull();
            assertThat(payload.eventId()).isEqualTo("order-confirmed:v1:" + seed.orderId());
            assertThat(payload.orderId()).isEqualTo(seed.orderId());
            assertThat(payload.confirmedAt()).isEqualTo(orderOf(seed).getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
            assertThat(payload.items()).containsExactly(new OrderConfirmedEvent.Item(seed.productId(), 2));
            given(clock.instant()).willReturn(NOW.plusSeconds(60));

            // act
            PaymentApplyResult applied = paymentResultService.applyFinal(
                seed.attemptId(), PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY));

            // assert - 2회차는 반영도 payload 도 없다
            assertThat(applied.applied()).isFalse();
            assertThat(applied.firstConfirmation()).isNull();
            PaymentAttempt attempt = attemptOf(seed);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
            assertThat(attempt.getFailureReason()).isNull();
            Order order = orderOf(seed);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(2000L);
            assertThat(order.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
            assertThat(order.getUpdatedAt().toInstant()).isEqualTo(CONFIRMED_AT);
            assertThat(reservationOf(seed).getStatus()).isEqualTo(StockReservation.Status.CONSUMED);
            assertThat(stockOf(seed.productId())).isEqualTo(3);
        }

        @DisplayName("SUCCESS 로 종결된 뒤 늦게 온 생성 응답(PENDING·UNKNOWN) 기록은 0행이고, 시도는 SUCCESS 와 기존 key 를 유지한다.")
        @Test
        void ignoresLateCreateResponse_whenAlreadySettled() {
            // arrange
            Seed seed = seedReservedOrder();
            assertThat(paymentResultService.applyFinal(seed.attemptId(), PaymentFinalResult.success(TRANSACTION_KEY)).applied()).isTrue();

            // act
            int pendingRows = paymentResultService.recordPending(seed.attemptId(), "LATE-KEY");
            int unknownRows = paymentResultService.recordUnknown(seed.attemptId());

            // assert
            assertThat(pendingRows).isZero();
            assertThat(unknownRows).isZero();
            PaymentAttempt attempt = attemptOf(seed);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
            assertThat(attempt.getTransactionKey()).isEqualTo(TRANSACTION_KEY);
            assertThat(orderOf(seed).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        }
    }

    @DisplayName("같은 TX 에서 주문 엔티티를 먼저 읽어 둔 뒤 적용해도, ")
    @Nested
    class OrderAlreadyLoadedInTransaction {
        @DisplayName("벌크 UPDATE 는 영속성 컨텍스트를 거치지 않아 캐시된 엔티티의 confirmedAt 은 null 로 남지만, payload 는 서비스가 만든 시각을 쓰고 commit 뒤 DB 값과 같다.")
        @Test
        void usesServiceClockValue_whenOrderEntityIsCachedInSameTransaction() {
            // arrange
            Seed seed = seedReservedOrder();

            // act - 호출자가 TX 를 열고 Order 를 먼저 읽은 상태에서 applyFinal 이 그 TX 에 참여한다
            PaymentApplyResult applied = transactionTemplate.execute(status -> {
                assertThat(orderJpaRepository.findById(seed.orderId()).orElseThrow().getConfirmedAt()).isNull();
                PaymentApplyResult result = paymentResultService.applyFinal(seed.attemptId(), PaymentFinalResult.success(TRANSACTION_KEY));
                assertThat(orderJpaRepository.findById(seed.orderId()).orElseThrow().getConfirmedAt())
                    .as("같은 TX 의 캐시된 엔티티는 벌크 UPDATE 결과를 모른다")
                    .isNull();
                return result;
            });

            // assert
            assertThat(applied.firstConfirmation()).isNotNull();
            assertThat(applied.firstConfirmation().confirmedAt()).isEqualTo(CONFIRMED_AT);
            assertThat(orderOf(seed).getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
            assertThat(applied.firstConfirmation().items()).containsExactly(new OrderConfirmedEvent.Item(seed.productId(), 2));
        }
    }

    @DisplayName("가짜 PG 를 올리지 않은 컨텍스트에서, ")
    @Nested
    class WithoutFakeGateway {
        @DisplayName("운영 PG 빈은 실제 HTTP adapter 이고 test 프로필(enabled=false)에서는 confirm 이 IllegalStateException 으로 크게 실패한다. 시도는 REQUESTED 로 남고 조용히 UNKNOWN 이 되지 않는다.")
        @Test
        void failsLoudly_whenConfirmReachesDisabledRealAdapter() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            Product product = new Product(1L, "상품", new Price(1000L));
            product.changeStock(5);
            Long productId = productJpaRepository.save(product).getId();
            Long orderId = orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productId, 2))).orderId();

            // act & assert - 빈 자체가 실제 adapter 여야 한다 (껍데기나 가짜가 아님)
            assertThat(paymentGateway).isInstanceOf(HttpPaymentGateway.class);
            assertThrows(IllegalStateException.class,
                () -> orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm("k1", "TEST", "0000-0000-0000-0000")));

            // assert - TX A 는 커밋됐고(재고 3·예약 RESERVED), 시도는 REQUESTED 그대로다
            PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.REQUESTED);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(stockReservationJpaRepository.findAllByOrderId(orderId))
                .singleElement().extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
        }
    }

    private Seed seedReservedOrder() {
        Long userId = userJpaRepository.save(new User()).getId();
        Product product = new Product(1L, "상품", new Price(1000L));
        product.changeStock(3);
        Long productId = productJpaRepository.save(product).getId();

        Long orderId = orderJpaRepository.save(new Order(userId, List.of(new OrderItem(productId, 2, 1000L)))).getId();
        jdbcTemplate.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG' WHERE id = ?", orderId);

        Long reservationId = stockReservationJpaRepository.save(new StockReservation(orderId, productId, 2)).getId();

        Long attemptId = paymentAttemptJpaRepository.save(new PaymentAttempt(
            orderId, userId, "k1", PaymentAttempt.providerOrderIdOf(orderId), 2000L, "TEST")).getId();
        jdbcTemplate.update("UPDATE payment_attempts SET status = 'PENDING', transaction_key = ? WHERE id = ?",
            TRANSACTION_KEY, attemptId);

        return new Seed(userId, productId, orderId, attemptId, reservationId);
    }

    private Order orderOf(Seed seed) {
        return orderJpaRepository.findById(seed.orderId()).orElseThrow();
    }

    private PaymentAttempt attemptOf(Seed seed) {
        return paymentAttemptJpaRepository.findById(seed.attemptId()).orElseThrow();
    }

    private StockReservation reservationOf(Seed seed) {
        return stockReservationJpaRepository.findById(seed.reservationId()).orElseThrow();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }
}