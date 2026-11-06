package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * W6 누락 복구용 backfill 규칙 (7주차 과제 5-5 📄 p.4, 설계 D34·D35). 실제 MySQL·가짜 PG 로 검증한다.
 * 핵심 사실: 확정 때 저장한 payload 와 backfill 이 저장 사실에서 다시 만든 payload 는 같은 코드·같은 ObjectMapper 라 문자열이 같다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class OrderConfirmedOutboxBackfillTest {

    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-07T03:00:00.123456789Z");
    private static final Instant BACKFILL_AT = Instant.parse("2026-11-07T03:00:00.123Z");

    @MockitoBean
    private Clock clock;

    @Autowired
    private OrderConfirmedOutboxBackfill backfill;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private PaymentFacade paymentFacade;
    @Autowired
    private FakePaymentGateway fakePaymentGateway;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderConfirmedOutboxRepository outboxRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserJpaRepository userJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Long userId;
    private Long productA;
    private Long productB;

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
        given(clock.instant()).willReturn(NOW);
        given(clock.getZone()).willReturn(ZONE);
        userId = userJpaRepository.save(new User()).getId();
        productA = saveProduct("상품 A", 1000L, 10);
        productB = saveProduct("상품 B", 2000L, 10);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Outbox 가 없는 CONFIRMED 주문에, ")
    @Nested
    class MissingOutbox {
        @DisplayName("저장된 confirmedAt·품목으로 Outbox 한 행을 PENDING 으로 추가한다. eventId·key·payload 는 확정 때와 같은 코드로 만든 값이고 nextAttemptAt 은 backfill 시각이다.")
        @Test
        void createsOneRow_fromStoredFacts() throws Exception {
            Long orderId = confirmWithoutOutbox();

            OrderConfirmedOutboxBackfill.Result result = backfill.backfill(orderId);

            assertThat(result).isEqualTo(OrderConfirmedOutboxBackfill.Result.CREATED);
            OrderConfirmedOutbox row = outboxRepository.findByOrderId(orderId).orElseThrow();
            Order order = orderJpaRepository.findWithItemsById(orderId).orElseThrow();
            OrderConfirmedEvent expected = OrderConfirmedEvent.of(orderId, order.getConfirmedAt(), order.getItems());
            assertThat(row.getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(row.getMessageKey()).isEqualTo(String.valueOf(orderId));
            assertThat(row.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
            assertThat(row.getAttemptCount()).isZero();
            assertThat(row.getPublishedAt()).isNull();
            assertThat(row.getNextAttemptAt().toInstant()).isEqualTo(BACKFILL_AT);
            assertThat(row.getPayload()).isEqualTo(objectMapper.writeValueAsString(expected));
            JsonNode payload = objectMapper.readTree(row.getPayload());
            assertThat(payload.get("confirmedAt").asText()).isEqualTo(order.getConfirmedAt().toInstant().toString());
            assertThat(payload.get("items")).hasSize(2);
            assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(productA);
            assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
            assertThat(payload.get("items").get(1).get("productId").asLong()).isEqualTo(productB);
            assertThat(payload.get("items").get(1).get("quantity").asInt()).isEqualTo(1);
        }

        @DisplayName("같은 주문을 두 번 backfill 해도 두 번째는 '이미 있음·일치' 이고 행은 하나다.")
        @Test
        void secondBackfillIsNoOp() {
            Long orderId = confirmWithoutOutbox();
            backfill.backfill(orderId);
            String payloadBefore = outboxRepository.findByOrderId(orderId).orElseThrow().getPayload();

            OrderConfirmedOutboxBackfill.Result second = backfill.backfill(orderId);

            assertThat(second).isEqualTo(OrderConfirmedOutboxBackfill.Result.ALREADY_PRESENT);
            assertThat(outboxRowCount(orderId)).isEqualTo(1);
            assertThat(outboxRepository.findByOrderId(orderId).orElseThrow().getPayload()).isEqualTo(payloadBefore);
        }
    }

    @DisplayName("Outbox 가 이미 있으면, ")
    @Nested
    class ExistingOutbox {
        @DisplayName("제품 확정 경로(applyFinal)가 만든 행과 backfill 이 다시 만든 payload 가 문자열까지 같아 '이미 있음·일치' 다. 행은 바뀌지 않는다.")
        @Test
        void matchesRowCreatedByConfirmationPath() {
            Long orderId = confirmThroughProductPath();
            OrderConfirmedOutbox before = outboxRepository.findByOrderId(orderId).orElseThrow();

            OrderConfirmedOutboxBackfill.Result result = backfill.backfill(orderId);

            assertThat(result).isEqualTo(OrderConfirmedOutboxBackfill.Result.ALREADY_PRESENT);
            OrderConfirmedOutbox after = outboxRepository.findByOrderId(orderId).orElseThrow();
            assertThat(after.getPayload()).isEqualTo(before.getPayload());
            assertThat(after.getStatus()).isEqualTo(before.getStatus());
            assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
            assertThat(outboxRowCount(orderId)).isEqualTo(1);
        }

        @DisplayName("내용이 다르면(payload 불일치) 추측하지 않고 CONFLICT 로 드러내고 행을 건드리지 않는다.")
        @Test
        void rejectsMismatch() {
            Long orderId = confirmWithoutOutbox();
            Order order = orderJpaRepository.findWithItemsById(orderId).orElseThrow();
            OrderConfirmedEvent event = OrderConfirmedEvent.of(orderId, order.getConfirmedAt(), order.getItems());
            String wrongPayload = "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"" + event.eventId()
                + "\",\"orderId\":" + orderId + ",\"confirmedAt\":\"2020-01-01T00:00:00Z\",\"items\":[]}";
            inTx(() -> outboxRepository.save(OrderConfirmedOutbox.pending(event, wrongPayload, order.getConfirmedAt())));

            assertThatThrownBy(() -> backfill.backfill(orderId))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.CONFLICT);
            assertThat(outboxRepository.findByOrderId(orderId).orElseThrow().getPayload()).isEqualTo(wrongPayload);
        }
    }

    @DisplayName("원본 사실이 부족하면 추측하지 않고 오류로 드러낸다: ")
    @Nested
    class InsufficientFacts {
        @DisplayName("주문이 없으면 NOT_FOUND.")
        @Test
        void unknownOrder() {
            assertThatThrownBy(() -> backfill.backfill(999_999L))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("CONFIRMED 가 아닌 주문(PAYMENT_PENDING)이면 CONFLICT 이고 Outbox 는 생기지 않는다.")
        @Test
        void notConfirmed() {
            Long orderId = createOrder();
            startPayment(orderId, "k1");
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);

            assertThatThrownBy(() -> backfill.backfill(orderId))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.CONFLICT);
            assertThat(outboxRowCount(orderId)).isZero();
        }

        @DisplayName("confirmedAt 이 없는 확정 주문(이전 POINT 확정)은 CONFLICT.")
        @Test
        void confirmedAtMissing() {
            Long orderId = confirmWithoutOutbox();
            jdbcTemplate.update("UPDATE orders SET confirmed_at = NULL WHERE id = ?", orderId);

            assertThatThrownBy(() -> backfill.backfill(orderId))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.CONFLICT);
            assertThat(outboxRowCount(orderId)).isZero();
        }

        @DisplayName("품목이 없는 확정 주문은 CONFLICT.")
        @Test
        void noItems() {
            Long orderId = confirmWithoutOutbox();
            jdbcTemplate.update("DELETE FROM order_items WHERE order_id = ?", orderId);

            assertThatThrownBy(() -> backfill.backfill(orderId))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.CONFLICT);
            assertThat(outboxRowCount(orderId)).isZero();
        }
    }

    // ---- fixtures ----

    /** 제품 확정 경로. applyFinal 이 Outbox 를 함께 만든다. */
    private Long confirmThroughProductPath() {
        Long orderId = createOrder();
        PaymentAttempt attempt = startPayment(orderId, "k1");
        fakePaymentGateway.markSuccess(attempt.getTransactionKey());
        paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());
        assertThat(outboxRowCount(orderId)).isEqualTo(1);
        return orderId;
    }

    /**
     * "확정은 됐지만 Outbox 는 없는" W6 상태를 만드는 fixture. 결제 시작까지는 기존 경로를 쓰고, 확정 UPDATE 만 도메인 리포지터리로 직접 수행한다.
     * (W6 재현 자체는 crossAppTest 의 W6 보존 경로가 맡는다. 여기서는 backfill 규칙만 본다.)
     */
    private Long confirmWithoutOutbox() {
        Long orderId = createOrder();
        PaymentAttempt attempt = startPayment(orderId, "k1");
        ZonedDateTime at = ZonedDateTime.now(clock).minusMinutes(5);
        int rows = inTx(() -> orderRepository.finishIfPaymentPending(orderId, Order.OrderStatus.CONFIRMED, attempt.getAmount(), at));
        assertThat(rows).isEqualTo(1);
        assertThat(outboxRowCount(orderId)).isZero();
        return orderId;
    }

    private Long createOrder() {
        return orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productA, 2), new OrderCommand.Item(productB, 1))).orderId();
    }

    private PaymentAttempt startPayment(Long orderId, String attemptKey) {
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private Long saveProduct(String name, long price, int stock) {
        Product product = new Product(1L, name, new Price(price));
        product.changeStock(stock);
        return productJpaRepository.save(product).getId();
    }

    private Order orderOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private int outboxRowCount(Long orderId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_confirmed_outbox WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
