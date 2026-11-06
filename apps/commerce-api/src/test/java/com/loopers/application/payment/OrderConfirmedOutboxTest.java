package com.loopers.application.payment;

import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PG 성공을 반영하는 확정 TX가 주문 확정과 함께 Outbox를 남기는지 확인한다. Kafka 는 쓰지 않는다. 합계는 3,500 × 2 + 2,000 × 1 = 9,000원이다. */
class OrderConfirmedOutboxTest extends PaymentFlowTestSupport {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxRepository outboxRepository;

    @DisplayName("PG 성공을 처음 반영하면 주문이 CONFIRMED 가 되고, 확정 사실을 담은 PENDING Outbox 가 한 행 남는다.")
    @Test
    void recordsPendingOutbox_onFirstSuccess() throws IOException {
        // given
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Product socks = saveProduct("양말", 2_000, 5);
        Long orderId = orderFacade.create(user.getId(), List.of(
            new OrderItemCommand(shoes.getId(), 2),
            new OrderItemCommand(socks.getId(), 1)
        )).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        PgPaymentStatusLookupResult success = PgPaymentStatusLookupResult.answered(List.of(
            new PgTransactionInfo(pgKeyOf(orderId), providerOrderIdOf(orderId), 9_000, PgStatus.SUCCESS)
        ));

        // when
        updateByStatusLookupResult(orderId, success);

        // then
        assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
        List<Outbox> outboxes = outboxRepository.findDueForDelivery(ZonedDateTime.now().plusDays(1), 10);
        assertThat(outboxes).hasSize(1);
        Outbox outbox = outboxes.get(0);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(outbox.getMessageKey()).isEqualTo(String.valueOf(orderId));
        OrderConfirmed message = OrderConfirmed.getDecoder().decode(outbox.getPayload());
        assertThat(message.getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(message.getOrderId()).isEqualTo(orderId);
        assertThat(message.getConfirmedAt()).isEqualTo(orderService.get(orderId).getConfirmedAt().toInstant());
        assertThat(message.getItems())
            .extracting(item -> item.getProductId() + "x" + item.getQuantity())
            .containsExactlyInAnyOrder(shoes.getId() + "x2", socks.getId() + "x1");
    }

    @DisplayName("같은 PG 성공을 다시 반영해도 Outbox 는 한 행이다.")
    @Test
    void recordsOneOutbox_whenSameSuccessAppliedAgain() {
        // given
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Product socks = saveProduct("양말", 2_000, 5);
        Long orderId = orderFacade.create(user.getId(), List.of(
            new OrderItemCommand(shoes.getId(), 2),
            new OrderItemCommand(socks.getId(), 1)
        )).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        PgPaymentStatusLookupResult success = PgPaymentStatusLookupResult.answered(List.of(
            new PgTransactionInfo(pgKeyOf(orderId), providerOrderIdOf(orderId), 9_000, PgStatus.SUCCESS)
        ));

        // when
        updateByStatusLookupResult(orderId, success);
        updateByStatusLookupResult(orderId, success);

        // then
        Long outboxCount = jdbcTemplate.queryForObject("select count(*) from outbox", Long.class);
        assertThat(outboxCount).isEqualTo(1L);
    }

    @DisplayName("처리 중이거나 실패한 결과는 Outbox 를 남기지 않는다.")
    @ParameterizedTest
    @EnumSource(value = PgStatus.class, names = {"PENDING", "FAILED"})
    void recordsNoOutbox_whenNotSuccess(PgStatus status) {
        // given
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Product socks = saveProduct("양말", 2_000, 5);
        Long orderId = orderFacade.create(user.getId(), List.of(
            new OrderItemCommand(shoes.getId(), 2),
            new OrderItemCommand(socks.getId(), 1)
        )).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        PgPaymentStatusLookupResult answer = PgPaymentStatusLookupResult.answered(List.of(
            new PgTransactionInfo(pgKeyOf(orderId), providerOrderIdOf(orderId), 9_000, status)
        ));

        // when
        updateByStatusLookupResult(orderId, answer);

        // then
        Long outboxCount = jdbcTemplate.queryForObject("select count(*) from outbox", Long.class);
        assertThat(outboxCount).isZero();
    }
}
