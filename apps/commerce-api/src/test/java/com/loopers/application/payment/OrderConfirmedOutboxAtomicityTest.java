package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.application.order.OrderConfirmedOutbox;
import com.loopers.application.order.OrderOutbox;
import com.loopers.application.order.OrderOutboxMessage;
import com.loopers.application.order.RecoverOrderConfirmedOutbox;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootTest
class OrderConfirmedOutboxAtomicityTest {
    private static final LocalPgServer PG = LocalPgServer.start();

    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private OrderPaymentFixture fixture;
    @Autowired private OrderFixture orders;
    @Autowired private RecoverOrderConfirmedOutbox recovery;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private OrderOutbox orderOutbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private OrderConfirmedOutbox outbox;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
    }

    @BeforeEach
    void resetPg() {
        PG.reset();
    }

    @AfterEach
    void clean() {
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @Test
    void 최초_PG_확정과_같은_사건이_outbox에_저장된다() throws Exception {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        assertThat(outboxCount(order.orderId())).isZero();

        reconcilePayment.reconcile(order.userId(), order.orderId());

        var rows = jdbc.queryForList("select event_id, message_key, payload, status"
                + " from order_outbox where order_id = ?", order.orderId());
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.get("event_id")).isEqualTo("order-confirmed:v1:" + order.orderId());
        assertThat(row.get("message_key")).isEqualTo(Long.toString(order.orderId()));
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(orders.order(order.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixture.findReservationByOrderAndProduct(order.orderId(), order.productId()).status())
                .isEqualTo(StockReservationStatus.CONSUMED);
        var event = objectMapper.readValue(row.get("payload").toString(), OrderConfirmed.class);
        assertThat(event.orderId()).isEqualTo(order.orderId());
        assertThat(event.items()).containsExactly(new OrderConfirmed.Item(order.productId(), order.orderQuantity()));
    }

    @Test
    void 트랜잭션_없이_주문_확정_이벤트를_발행하면_리스너가_트랜잭션을_시작해_저장한다() {
        var event = OrderConfirmed.confirmed(100L, Instant.parse("2026-10-29T01:00:00Z"),
                List.of(new OrderConfirmed.Item(10L, 2)));
        var transactionActiveDuringRecord = new AtomicBoolean();
        doAnswer(invocation -> {
            transactionActiveDuringRecord.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(outbox).record(any(OrderConfirmed.class));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(outboxCount(event.orderId())).isZero();

        events.publishEvent(event);

        assertThat(transactionActiveDuringRecord).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(outboxCount(event.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select event_id from order_outbox where order_id = ?",
                String.class, event.orderId())).isEqualTo(event.eventId());
    }

    @Test
    void 트랜잭션_없이_outbox_저장과_중복_확인_저장을_호출해도_커밋된다() {
        var event = new OrderOutboxMessage("standalone-100", 100L, "OrderCanceled", 1,
                "100", "order-events", "{\"orderId\":100}");
        var recoveryEvent = new OrderOutboxMessage("standalone-101", 101L, "OrderCanceled", 1,
                "101", "order-events", "{\"orderId\":101}");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(outboxCount(100L)).isZero();
        assertThat(outboxCount(101L)).isZero();

        orderOutbox.record(event);
        boolean created = orderOutbox.recordIfAbsent(recoveryEvent);
        boolean duplicate = orderOutbox.recordIfAbsent(recoveryEvent);

        assertThat(created).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(outboxCount(100L)).isEqualTo(1);
        assertThat(outboxCount(101L)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void outbox_저장_전후_오류는_기존_주문과_예약을_롤백하고_같은_거래로_재개한다(boolean failAfterSave) {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        assertThat(orders.order(order.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.findPaymentByOrderId(order.orderId()).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(fixture.findReservationByOrderAndProduct(order.orderId(), order.productId()).status())
                .isEqualTo(StockReservationStatus.HELD);
        assertThat(outboxCount(order.orderId())).isZero();
        doAnswer(invocation -> {
            if (failAfterSave) {
                invocation.callRealMethod();
            }
            throw new IllegalStateException("controlled outbox failure");
        }).when(outbox).record(any(OrderConfirmed.class));

        assertThatThrownBy(() -> reconcilePayment.reconcile(order.userId(), order.orderId()))
                .hasMessageContaining("controlled outbox failure");

        assertThat(orders.order(order.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.findPaymentByOrderId(order.orderId()).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(fixture.findReservationByOrderAndProduct(order.orderId(), order.productId()).status())
                .isEqualTo(StockReservationStatus.HELD);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox where order_id = ?",
                Long.class, order.orderId())).isZero();
        assertThat(PG.createRequestCount()).isEqualTo(1);

        reset(outbox);
        reconcilePayment.reconcile(order.userId(), order.orderId());
        reconcilePayment.reconcile(order.userId(), order.orderId());

        assertThat(orders.order(order.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixture.findReservationByOrderAndProduct(order.orderId(), order.productId()).status())
                .isEqualTo(StockReservationStatus.CONSUMED);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox where order_id = ?",
                Long.class, order.orderId())).isEqualTo(1);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void PG가_대기이면_확정_outbox를_만들지_않는다() {
        var order = fixture.createUnpaidOrder();
        requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        assertThat(outboxCount(order.orderId())).isZero();

        reconcilePayment.reconcile(order.userId(), order.orderId());

        assertThat(fixture.findPaymentByOrderId(order.orderId()).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outboxCount(order.orderId())).isZero();
    }

    @Test
    void PG가_실패이면_확정_outbox를_만들지_않는다() {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.failPaymentForOrder(accepted.providerOrderId());
        assertThat(outboxCount(order.orderId())).isZero();

        reconcilePayment.reconcile(order.userId(), order.orderId());

        assertThat(fixture.findPaymentByOrderId(order.orderId()).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(outboxCount(order.orderId())).isZero();
    }

    @Test
    void PG_응답이_유실되어_UNKNOWN이면_outbox를_만들지_않는다() {
        var order = fixture.createUnpaidOrder();
        PG.loseResponseAfterCreatingTransaction();
        assertThat(outboxCount(order.orderId())).isZero();

        var result = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(orders.order(order.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(outboxCount(order.orderId())).isZero();
    }

    @Test
    void 기존_outbox의_내용이_저장된_주문과_다르면_복구에서_충돌을_드러낸다() throws Exception {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        reconcilePayment.reconcile(order.userId(), order.orderId());
        String original = jdbc.queryForObject("select payload from order_outbox where order_id = ?",
                String.class, order.orderId());
        var payload = objectMapper.readTree(original);
        ((ObjectNode) payload.path("items").get(0)).put("quantity", order.orderQuantity() + 1);
        String conflicting = objectMapper.writeValueAsString(payload);
        jdbc.update("update order_outbox set payload = ? where order_id = ?", conflicting, order.orderId());
        int lookupCount = PG.lookupRequestCount();

        assertThatThrownBy(() -> recovery.recover(order.orderId()))
                .hasMessageContaining("does not match requested event");

        assertThat(jdbc.queryForObject("select payload from order_outbox where order_id = ?",
                String.class, order.orderId())).isEqualTo(conflicting);
        assertThat(PG.lookupRequestCount()).isEqualTo(lookupCount);
    }

    @Test
    void 기존_payload에_알수없는_필드가_있어도_복구에서_충돌로_드러낸다() throws Exception {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        reconcilePayment.reconcile(order.userId(), order.orderId());
        String original = jdbc.queryForObject("select payload from order_outbox where order_id = ?",
                String.class, order.orderId());
        var payload = (ObjectNode) objectMapper.readTree(original);
        payload.put("unexpectedBusinessFact", "changed");
        String conflicting = objectMapper.writeValueAsString(payload);
        jdbc.update("update order_outbox set payload = ? where order_id = ?", conflicting, order.orderId());

        assertThatThrownBy(() -> recovery.recover(order.orderId()))
                .hasMessageContaining("does not match requested event");
        assertThat(jdbc.queryForObject("select payload from order_outbox where order_id = ?",
                String.class, order.orderId())).isEqualTo(conflicting);
    }

    private long outboxCount(long orderId) {
        return jdbc.queryForObject("select count(*) from order_outbox where order_id = ?",
                Long.class, orderId);
    }
}
