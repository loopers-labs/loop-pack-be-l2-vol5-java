package com.loopers.application.payment;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakeOrderConfirmedPublisher;
import com.loopers.infrastructure.payment.FakeOrderConfirmedPublisherConfig;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * "검증된 PG SUCCESS 를 처음 CONFIRMED 로 반영해 commit 된 경우에만 발행한다"(과제 2절, 설계 D3·D4·D7)를
 * 실제 MySQL·가짜 PG·가짜 발행기로 검증한다. 발행은 callback·reconcile 이 공유하는 PaymentFacade.apply 에서만 일어난다.
 */
@SpringBootTest
@Import({FakePaymentGatewayConfig.class, FakeOrderConfirmedPublisherConfig.class})
class OrderConfirmedPublicationTest {

    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";

    @MockitoSpyBean
    private StockReservationRepository stockReservationRepository;

    private final PaymentFacade paymentFacade;
    private final OrderFacade orderFacade;
    private final FakePaymentGateway fakePaymentGateway;
    private final FakeOrderConfirmedPublisher fakePublisher;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderConfirmedPublicationTest(
        PaymentFacade paymentFacade,
        OrderFacade orderFacade,
        FakePaymentGateway fakePaymentGateway,
        FakeOrderConfirmedPublisher fakePublisher,
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
        this.fakePaymentGateway = fakePaymentGateway;
        this.fakePublisher = fakePublisher;
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
        fakePublisher.reset();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("PG SUCCESS 를 처음 반영하면, ")
    @Nested
    class FirstConfirmation {
        @DisplayName("저장된 품목·확정 시각으로 만든 이벤트를 1건 발행하고, 발행 시점에는 활성 TX 가 없으며 별도 연결로 읽은 주문이 이미 CONFIRMED 다.")
        @Test
        void publishesOnce_afterCommit() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            // send 직전에 다른 연결(JdbcTemplate, TX 없음)로 주문 상태를 읽어 commit 여부를 본다. 같은 영속성 컨텍스트가 아니다.
            List<String> statusAtPublish = new CopyOnWriteArrayList<>();
            fakePublisher.beforeSend(event -> statusAtPublish.add(
                jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, event.orderId())));

            // act
            OrderConfirmInfo info = paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());

            // assert
            assertThat(info.settled()).isTrue();
            assertThat(fakePublisher.published()).hasSize(1);
            OrderConfirmedEvent event = fakePublisher.published().get(0);
            assertThat(event.eventId()).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(event.orderId()).isEqualTo(orderId);
            assertThat(event.key()).isEqualTo(String.valueOf(orderId));
            assertThat(event.confirmedAt()).isEqualTo(orderOf(orderId).getConfirmedAt().toInstant());
            assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(productId, 2));
            assertThat(fakePublisher.publishedInsideTransaction()).as("발행 시점에 활성 DB 트랜잭션이 없어야 한다").isFalse();
            assertThat(statusAtPublish).containsExactly("CONFIRMED");
        }

        @DisplayName("같은 callback 이 두 번 오고 reconcile 까지 와도 이벤트는 1건, 재고 차감도 한 번이다.")
        @Test
        void publishesOnlyOnce_whenCallbackAndReconcileRepeat() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());
            paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());
            paymentFacade.reconcile(userId, orderId);

            // assert
            assertThat(fakePublisher.published()).hasSize(1);
            assertThat(fakePublisher.published().get(0).eventId()).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("확정이 아니면 발행하지 않는다: ")
    @Nested
    class NoPublication {
        @DisplayName("PG 가 아직 PENDING(대기)이면 발행 없음, 주문은 PAYMENT_PENDING.")
        @Test
        void nothing_whenPending() {
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");

            paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());
            paymentFacade.reconcile(userId, orderId);

            assertThat(fakePublisher.publishCallCount()).isZero();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("시도가 UNKNOWN(응답 유실)이고 조회 결과도 PENDING 이면 발행 없음.")
        @Test
        void nothing_whenUnknown() {
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            fakePaymentGateway.willCreateButLoseResponse();
            orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm("k1", CARD_TYPE, CARD_NO));
            fakePaymentGateway.willAcceptPending();
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.UNKNOWN);

            paymentFacade.reconcile(userId, orderId);

            assertThat(fakePublisher.publishCallCount()).isZero();
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("PG FAILED 와 생성 전 거절(확정 실패)은 발행 없음, 주문은 PAYMENT_FAILED.")
        @Test
        void nothing_whenFailed() {
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long failedOrderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, failedOrderId, "k1");
            fakePaymentGateway.markFailed(attempt.getTransactionKey());
            Long rejectedOrderId = createOrder(userId, productId, 1);
            fakePaymentGateway.willRejectNoEffect();

            paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());
            orderFacade.confirmOrder(userId, rejectedOrderId, new OrderCommand.Confirm("k2", CARD_TYPE, CARD_NO));

            assertThat(fakePublisher.publishCallCount()).isZero();
            assertThat(orderOf(failedOrderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(orderOf(rejectedOrderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(orderOf(failedOrderId).getConfirmedAt()).isNull();
        }

        @DisplayName("확정 TX 가 rollback 되면(applyFinal 예외) 발행 없이 예외가 Facade 밖으로 그대로 전파되고, 재시도에서 1건 발행된다.")
        @Test
        void nothing_whenConfirmationRollsBack_andExceptionPropagates() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            doThrow(new IllegalStateException("테스트: 예약 갱신 실패"))
                .doCallRealMethod()
                .when(stockReservationRepository).consumeIfReserved(anyLong());

            // act 1 - applyFinal 안(⑧)에서 예외. 발행 catch 에 삼켜지지 않는다
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey()));

            // assert 1
            assertThat(failure.getMessage()).isEqualTo("테스트: 예약 갱신 실패");
            assertThat(fakePublisher.publishCallCount()).isZero();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(orderOf(orderId).getConfirmedAt()).isNull();

            // act 2 - 같은 결과 재적용
            paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());

            // assert 2
            assertThat(fakePublisher.published()).hasSize(1);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("발행이 성공하지 못해도, ")
    @Nested
    class PublishFailure {
        @DisplayName("실패 확정(FAILED)이면 예외 없이 저장 결과로 응답하고 주문 CONFIRMED·예약 CONSUMED 는 그대로이며 PG create 를 다시 보내지 않는다.")
        @Test
        void keepsOrder_whenPublishFails() {
            assertOrderKept_whenPublishEndsWith(OrderConfirmedPublishException.Outcome.FAILED);
        }

        @DisplayName("결과 미확인(UNKNOWN)이어도 같다.")
        @Test
        void keepsOrder_whenPublishUnknown() {
            assertOrderKept_whenPublishEndsWith(OrderConfirmedPublishException.Outcome.UNKNOWN);
        }

        private void assertOrderKept_whenPublishEndsWith(OrderConfirmedPublishException.Outcome outcome) {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            fakePublisher.willFailWith(outcome);

            // act
            OrderConfirmInfo info = paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());

            // assert
            assertThat(info.settled()).isTrue();
            assertThat(info.attemptStatus()).isEqualTo("SUCCESS");
            assertThat(fakePublisher.publishCallCount()).isEqualTo(1);
            assertThat(fakePublisher.published()).isEmpty();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(orderOf(orderId).getConfirmedAt()).isNotNull();
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.CONSUMED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }
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

    /** confirm 으로 PENDING + transactionKey 상태의 시도를 만든다. */
    private PaymentAttempt startPayment(Long userId, Long orderId, String attemptKey) {
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        return attemptOf(orderId);
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
