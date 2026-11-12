package com.loopers.application.order.event;

import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.application.payment.PaymentStatusUpdater;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStockService;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.outbox.MissingOutboxRecovery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * W6 의 누락 상태(확정은 됐지만 Outbox 가 없음)를 재현하고, 테스트용 복구 도구가 저장된 확정 사실로 같은 사건의 Outbox 를
 * 채우는지 확인한다. W6 경로는 실제 PaymentStatusUpdater 에 아무것도 기록하지 않는 Outbox 기록기를 넣어 흉내 낸다.
 */
class OrderConfirmedOutboxRecoveryTest extends PaymentFlowTestSupport {

    @Autowired
    private OrderConfirmedOutboxRecorder recorder;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentAttemptService paymentAttemptService;

    @Autowired
    private OrderStockService orderStockService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @DisplayName("W6 처럼 Outbox 없이 확정된 주문은, 저장된 확정 사실 그대로 같은 eventId 의 Outbox 를 얻는다. PG·주문·재고는 건드리지 않는다.")
    @Test
    void recordsOutboxWithStoredFacts_whenMissing() throws IOException {
        // given: 러닝화 3,500 × 2 + 양말 2,000 × 1 = 9,000원 주문이 결제 시작(PENDING)까지 진행됐다.
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Product socks = saveProduct("양말", 2_000, 5);
        Long orderId = orderFacade.create(user.getId(), List.of(
            new OrderItemCommand(shoes.getId(), 2),
            new OrderItemCommand(socks.getId(), 1)
        )).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        // W6 경로: 확정 TX 는 그대로 실행하고 Outbox 만 남기지 않는다.
        OrderConfirmedOutboxRecorder w6NoOutbox = new OrderConfirmedOutboxRecorder(null) {
            @Override
            public void record(Order order, ZonedDateTime recordedAt) {
            }
        };
        PaymentStatusUpdater w6Updater =
            new PaymentStatusUpdater(orderService, paymentAttemptService, orderStockService, w6NoOutbox, clock);
        PgPaymentStatusLookupResult success = PgPaymentStatusLookupResult.answered(List.of(
            new PgTransactionInfo(pgKeyOf(orderId), providerOrderIdOf(orderId), 9_000, PgStatus.SUCCESS)
        ));
        new TransactionTemplate(transactionManager).execute(status -> w6Updater.updateByStatusLookupResult(orderId, success));
        String eventId = "order-confirmed:v1:" + orderId;
        assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbox", Long.class)).isZero();
        int pgCreateCallsBefore = pg.createCalls();
        int pgLookupCallsBefore = pg.lookupByKeyCalls();
        MissingOutboxRecovery recovery = new MissingOutboxRecovery(orderService, recorder, jdbcTemplate, transactionManager, clock);

        // when
        recovery.recover(orderId);

        // then: 같은 eventId, 주문에 저장된 확정 시각과 품목으로 PENDING Outbox 가 생긴다.
        assertThat(jdbcTemplate.queryForObject("select status from outbox where event_id = ?", String.class, eventId))
            .isEqualTo("PENDING");
        byte[] payload = jdbcTemplate.queryForObject("select payload from outbox where event_id = ?", byte[].class, eventId);
        OrderConfirmed message = OrderConfirmed.getDecoder().decode(payload);
        assertThat(message.getEventId()).isEqualTo(eventId);
        assertThat(message.getConfirmedAt()).isEqualTo(orderService.get(orderId).getConfirmedAt().toInstant());
        assertThat(message.getItems())
            .extracting(item -> item.getProductId() + "x" + item.getQuantity())
            .containsExactlyInAnyOrder(shoes.getId() + "x2", socks.getId() + "x1");
        // 복구는 PG 를 부르지 않고 주문·재고를 바꾸지 않는다.
        assertThat(pg.createCalls()).isEqualTo(pgCreateCallsBefore);
        assertThat(pg.lookupByKeyCalls()).isEqualTo(pgLookupCallsBefore);
        assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
        assertThat(stockOf(shoes)).isEqualTo(3);
        assertThat(stockOf(socks)).isEqualTo(4);
    }

    @DisplayName("같은 주문을 한 번 더 복구해도 Outbox 는 늘거나 바뀌지 않는다.")
    @Test
    void doesNothing_whenSameOutboxExists() {
        // given: W6 경로로 확정한 주문을 한 번 복구했다.
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Long orderId = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 2))).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        OrderConfirmedOutboxRecorder w6NoOutbox = new OrderConfirmedOutboxRecorder(null) {
            @Override
            public void record(Order order, ZonedDateTime recordedAt) {
            }
        };
        PaymentStatusUpdater w6Updater =
            new PaymentStatusUpdater(orderService, paymentAttemptService, orderStockService, w6NoOutbox, clock);
        PgPaymentStatusLookupResult success = PgPaymentStatusLookupResult.answered(List.of(
            new PgTransactionInfo(pgKeyOf(orderId), providerOrderIdOf(orderId), 7_000, PgStatus.SUCCESS)
        ));
        new TransactionTemplate(transactionManager).execute(status -> w6Updater.updateByStatusLookupResult(orderId, success));
        MissingOutboxRecovery recovery = new MissingOutboxRecovery(orderService, recorder, jdbcTemplate, transactionManager, clock);
        recovery.recover(orderId);
        Map<String, Object> first = jdbcTemplate.queryForMap("select id, created_at, payload from outbox");

        // when
        recovery.recover(orderId);

        // then: 여전히 한 행이고, 첫 복구 때의 행 그대로다.
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbox", Long.class)).isEqualTo(1L);
        Map<String, Object> second = jdbcTemplate.queryForMap("select id, created_at, payload from outbox");
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(second.get("created_at")).isEqualTo(first.get("created_at"));
        assertThat((byte[]) second.get("payload")).containsExactly((byte[]) first.get("payload"));
    }

    @DisplayName("확정되지 않은 주문은 사실이 부족하므로 Outbox 를 만들지 않고 CONFLICT 로 거절한다.")
    @Test
    void refusesWithoutRecording_whenOrderIsNotConfirmed() {
        // given: 결제 시작(PAYMENT_PENDING)까지만 진행한 주문
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Long orderId = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 2))).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
        MissingOutboxRecovery recovery = new MissingOutboxRecovery(orderService, recorder, jdbcTemplate, transactionManager, clock);

        // when
        CoreException exception = assertThrows(CoreException.class, () -> recovery.recover(orderId));

        // then
        assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbox", Long.class)).isZero();
    }
}
