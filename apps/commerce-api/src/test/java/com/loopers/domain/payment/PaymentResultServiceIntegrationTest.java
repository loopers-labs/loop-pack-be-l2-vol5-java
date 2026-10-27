package com.loopers.domain.payment;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.Order;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        DatabaseCleanUp databaseCleanUp
    ) {
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
            boolean first = paymentResultService.applyFinal(seed.attemptId(), failed);
            boolean second = paymentResultService.applyFinal(seed.attemptId(), failed);

            // assert
            assertThat(first).isTrue();
            assertThat(second).isFalse();
            assertThat(stockOf(seed.productId())).isEqualTo(5);
            assertThat(reservationOf(seed).getStatus()).isEqualTo(StockReservation.Status.RELEASED);
            PaymentAttempt attempt = attemptOf(seed);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.FAILED);
            assertThat(attempt.getFailureReason()).isEqualTo(PaymentAttempt.FailureReason.PG_FAILED);
            Order order = orderOf(seed);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(order.getPaidAmount()).isZero();
        }

        @DisplayName("같은 FAILED 결과를 8개 스레드가 동시에 적용하면, 정확히 하나만 반영되고 재고는 5 다.")
        @Test
        void appliesOnlyOnce_whenSameResultIsAppliedConcurrently() throws InterruptedException {
            // arrange
            Seed seed = seedReservedOrder();
            PaymentFinalResult failed = PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY);
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> paymentResultService.applyFinal(seed.attemptId(), failed));
            }

            // act
            List<ConcurrentRunner.Run<Boolean>> runs = ConcurrentRunner.runAll(tasks, TIMEOUT);

            // assert
            List<Throwable> errors = runs.stream().filter(ConcurrentRunner.Run::failed).map(ConcurrentRunner.Run::error).toList();
            assertThat(errors).withFailMessage("오류 %d건: %s", errors.size(), errors).isEmpty();
            long applied = runs.stream().filter(run -> Boolean.TRUE.equals(run.value())).count();
            assertThat(applied).isEqualTo(1);
            assertThat(stockOf(seed.productId())).isEqualTo(5);
            assertThat(reservationOf(seed).getStatus()).isEqualTo(StockReservation.Status.RELEASED);
            assertThat(attemptOf(seed).getStatus()).isEqualTo(PaymentAttempt.Status.FAILED);
        }
    }

    @DisplayName("이미 종결된 시도에 다른 결과가 오면, ")
    @Nested
    class AfterSettlement {
        @DisplayName("SUCCESS 로 종결된 시도에 FAILED 를 적용해도, 아무것도 바뀌지 않는다 (덮어쓰지 않음).")
        @Test
        void doesNotOverwrite_whenFailedArrivesAfterSuccess() {
            // arrange
            Seed seed = seedReservedOrder();
            assertThat(paymentResultService.applyFinal(seed.attemptId(), PaymentFinalResult.success(TRANSACTION_KEY))).isTrue();

            // act
            boolean applied = paymentResultService.applyFinal(
                seed.attemptId(), PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY));

            // assert
            assertThat(applied).isFalse();
            PaymentAttempt attempt = attemptOf(seed);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
            assertThat(attempt.getFailureReason()).isNull();
            Order order = orderOf(seed);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(2000L);
            assertThat(reservationOf(seed).getStatus()).isEqualTo(StockReservation.Status.CONSUMED);
            assertThat(stockOf(seed.productId())).isEqualTo(3);
        }

        @DisplayName("SUCCESS 로 종결된 뒤 늦게 온 생성 응답(PENDING·UNKNOWN) 기록은 0행이고, 시도는 SUCCESS 와 기존 key 를 유지한다.")
        @Test
        void ignoresLateCreateResponse_whenAlreadySettled() {
            // arrange
            Seed seed = seedReservedOrder();
            assertThat(paymentResultService.applyFinal(seed.attemptId(), PaymentFinalResult.success(TRANSACTION_KEY))).isTrue();

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