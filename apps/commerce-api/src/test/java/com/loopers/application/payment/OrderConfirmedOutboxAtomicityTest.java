package com.loopers.application.payment;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * "검증된 PG SUCCESS 를 처음 CONFIRMED 로 반영하는 같은 TX 에서만 OrderConfirmed Outbox 가 PENDING 으로 생긴다"
 * (7주차 과제 2절 📄 p.2, 설계 D10·D13·D16)를 실제 MySQL·가짜 PG 로 검증한다.
 * Outbox insert 는 callback·reconcile·스케줄러가 공유하는 PaymentResultService.applyFinal 에서만 일어난다.
 * 전환 전 테스트(OrderConfirmedPublicationTest)와의 대응은 설계 문서 D20 표에 있다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class OrderConfirmedOutboxAtomicityTest {

    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";

    @MockitoSpyBean
    private StockReservationRepository stockReservationRepository;
    @MockitoSpyBean
    private OrderConfirmedOutboxRepository outboxRepository;
    @MockitoSpyBean
    private ObjectMapper objectMapper;

    private final PaymentFacade paymentFacade;
    private final OrderFacade orderFacade;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderConfirmedOutboxAtomicityTest(
        PaymentFacade paymentFacade,
        OrderFacade orderFacade,
        FakePaymentGateway fakePaymentGateway,
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

    @DisplayName("PG SUCCESS 를 처음 반영하면, ")
    @Nested
    class FirstConfirmation {
        @DisplayName("Outbox 1행이 PENDING 으로 생기고 payload 는 저장 품목·확정 시각으로 만든 W6 계약 JSON 이며, 별도 연결로 읽어도 주문 CONFIRMED 와 Outbox 가 함께 보인다.")
        @Test
        void insertsOutboxOnce_inSameTransaction() throws Exception {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act
            OrderConfirmInfo info = paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());

            // assert - Outbox 행
            assertThat(info.settled()).isTrue();
            OrderConfirmedOutbox outbox = outboxOf(orderId).orElseThrow();
            assertThat(outbox.getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(outbox.getOrderId()).isEqualTo(orderId);
            assertThat(outbox.getMessageKey()).isEqualTo(String.valueOf(orderId));
            assertThat(outbox.getEventType()).isEqualTo(OrderConfirmedEvent.EVENT_TYPE);
            assertThat(outbox.getSchemaVersion()).isEqualTo(OrderConfirmedEvent.SCHEMA_VERSION);
            assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
            assertThat(outbox.getPublishedAt()).isNull();
            assertThat(outbox.getAttemptCount()).isZero();
            assertThat(outbox.getLastError()).isNull();
            assertThat(outbox.getNextAttemptAt().toInstant()).isEqualTo(orderOf(orderId).getConfirmedAt().toInstant());

            // assert - payload 는 W6 계약 JSON (eventType, schemaVersion, eventId, orderId, confirmedAt ISO 문자열, items)
            JsonNode payload = objectMapper.readTree(outbox.getPayload());
            assertThat(payload.get("eventType").asText()).isEqualTo("OrderConfirmed");
            assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(payload.get("eventId").asText()).isEqualTo(outbox.getEventId());
            assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
            assertThat(payload.get("confirmedAt").asText()).isEqualTo(orderOf(orderId).getConfirmedAt().toInstant().toString());
            assertThat(payload.get("items")).hasSize(1);
            assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(productId);
            assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);

            // assert - 다른 연결(JdbcTemplate, TX 없음)로 읽어도 둘 다 commit 돼 있다
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("CONFIRMED");
            assertThat(outboxRowCount(orderId)).isEqualTo(1);
        }

        @DisplayName("같은 callback 이 두 번 오고 reconcile 까지 와도 Outbox 는 1행, 재고 차감도 한 번이다.")
        @Test
        void insertsOnlyOnce_whenCallbackAndReconcileRepeat() {
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
            assertThat(outboxRowCount(orderId)).isEqualTo(1);
            assertThat(outboxOf(orderId).orElseThrow().getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("확정이 아니면 Outbox 를 만들지 않는다: ")
    @Nested
    class NoOutbox {
        @DisplayName("PG 가 아직 PENDING(대기)이면 Outbox 없음, 주문은 PAYMENT_PENDING.")
        @Test
        void nothing_whenPending() {
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");

            paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());
            paymentFacade.reconcile(userId, orderId);

            assertThat(outboxRowCount(orderId)).isZero();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("시도가 UNKNOWN(응답 유실)이고 조회 결과도 PENDING 이면 Outbox 없음.")
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

            assertThat(outboxRowCount(orderId)).isZero();
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("PG FAILED 와 생성 전 거절(확정 실패)은 Outbox 없음, 주문은 PAYMENT_FAILED.")
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

            assertThat(outboxRowCount(failedOrderId)).isZero();
            assertThat(outboxRowCount(rejectedOrderId)).isZero();
            assertThat(orderOf(failedOrderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(orderOf(rejectedOrderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(orderOf(failedOrderId).getConfirmedAt()).isNull();
        }

        @DisplayName("확정 TX 가 rollback 되면(예약 갱신 예외) Outbox 도 없고 예외가 Facade 밖으로 그대로 전파되며, 재시도에서 Outbox 1행이 생긴다.")
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

            // act 1 - applyFinal 안(⑧)에서 예외
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey()));

            // assert 1
            assertThat(failure.getMessage()).isEqualTo("테스트: 예약 갱신 실패");
            assertThat(outboxRowCount(orderId)).isZero();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(orderOf(orderId).getConfirmedAt()).isNull();

            // act 2 - 같은 결과 재적용
            paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());

            // assert 2
            assertThat(outboxRowCount(orderId)).isEqualTo(1);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("Outbox 저장에 실패하면 확정 TX 전체가 rollback 된다: ")
    @Nested
    class OutboxFailure {
        @DisplayName("payload 직렬화 실패(OrderConfirmedEvent 직렬화에만 주입)면 주문 PAYMENT_PENDING·예약 RESERVED·재고 미차감·Outbox 0행으로 남고 PG create 는 추가되지 않으며, 재시도에서 확정·Outbox 1행이 된다.")
        @Test
        void rollsBackConfirmation_whenSerializationFails() throws Exception {
            doThrow(JsonMappingException.from((JsonGenerator) null, "테스트: 직렬화 실패"))
                .doCallRealMethod()
                .when(objectMapper).writeValueAsString(any(OrderConfirmedEvent.class));

            assertRolledBack_thenRetrySucceeds(CoreException.class);
        }

        @DisplayName("Outbox insert 실패여도 같다.")
        @Test
        void rollsBackConfirmation_whenOutboxInsertFails() {
            doThrow(new IllegalStateException("테스트: Outbox 저장 실패"))
                .doCallRealMethod()
                .when(outboxRepository).save(any(OrderConfirmedOutbox.class));

            assertRolledBack_thenRetrySucceeds(IllegalStateException.class);
        }

        private void assertRolledBack_thenRetrySucceeds(Class<? extends RuntimeException> expected) {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            PaymentAttempt attempt = startPayment(userId, orderId, "k1");
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());

            // act 1 - 같은 TX 안의 Outbox 실패. 예외는 밖으로 전파된다
            assertThrows(expected, () -> paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey()));

            // assert 1 - 기존 주문·시도·예약은 TX 직전 상태 그대로 (주문 행이 사라지는 것이 아니다). 외부 PG 거래는 그대로 1건
            assertThat(outboxRowCount(orderId)).isZero();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(orderOf(orderId).getConfirmedAt()).isNull();
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);

            // act 2 - 같은 거래 재조회로 다시 적용 (create 없음)
            OrderConfirmInfo info = paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());

            // assert 2
            assertThat(info.settled()).isTrue();
            assertThat(outboxRowCount(orderId)).isEqualTo(1);
            assertThat(outboxOf(orderId).orElseThrow().getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(reservationStatusesOf(orderId)).containsExactly(StockReservation.Status.CONSUMED);
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

    private Optional<OrderConfirmedOutbox> outboxOf(Long orderId) {
        return outboxRepository.findByOrderId(orderId);
    }

    /** 별도 연결(TX 없음)로 세는 Outbox 행 수. */
    private int outboxRowCount(Long orderId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_confirmed_outbox WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private List<StockReservation.Status> reservationStatusesOf(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId).stream().map(StockReservation::getStatus).toList();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }
}
