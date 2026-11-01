package com.loopers.application.payment;

import com.loopers.application.order.dto.OrderConfirmedInfo;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.payment.dto.PaymentStatusUpdateResult;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 주문을 처음 확정한 반영만 확정 사실(payload)을 돌려주는지 확인한다. 합계는 3,500 × 2 + 2,000 × 1 = 9,000원이다. */
class OrderConfirmedPayloadTest extends PaymentFlowTestSupport {

    private static final long TOTAL = 9_000L;

    @Autowired
    private OrderService orderService;

    private User user;
    private Product shoes;
    private Product socks;
    private Long orderId;
    private String transactionKey;

    private void startPending() {
        user = saveUser(0);
        shoes = saveProduct("러닝화", 3_500, 5);
        socks = saveProduct("양말", 2_000, 5);
        orderId = orderFacade.create(user.getId(), List.of(
            new OrderItemCommand(shoes.getId(), 2),
            new OrderItemCommand(socks.getId(), 1)
        )).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        transactionKey = pgKeyOf(orderId);
    }

    private PaymentStatusUpdateResult observe(PgStatus status) {
        return updateByStatusLookupResult(orderId, PgPaymentStatusLookupResult.answered(
            List.of(new PgTransactionInfo(transactionKey, providerOrderIdOf(orderId), TOTAL, status))
        ));
    }

    @DisplayName("PG 성공을 처음 반영하면 저장된 품목·수량·확정 시각으로 만든 확정 사실을 돌려주고, 같은 성공을 다시 반영하면 없다.")
    @Test
    void returnsConfirmed_onlyOnFirstSuccess() {
        // arrange
        startPending();

        // act
        PaymentStatusUpdateResult first = observe(PgStatus.SUCCESS);
        PaymentStatusUpdateResult repeated = observe(PgStatus.SUCCESS);

        // assert
        OrderConfirmedInfo confirmed = first.confirmed();
        assertThat(confirmed.eventId()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(confirmed.orderId()).isEqualTo(orderId);
        assertThat(confirmed.confirmedAt().toInstant())
            .isEqualTo(orderService.get(orderId).getConfirmedAt().toInstant());
        assertThat(confirmed.items()).containsExactlyInAnyOrder(
            new OrderConfirmedInfo.Item(shoes.getId(), 2),
            new OrderConfirmedInfo.Item(socks.getId(), 1)
        );
        assertThat(repeated.confirmed()).isNull();
    }

    @DisplayName("처리 중이거나 실패한 결과는 확정 사실이 없다.")
    @ParameterizedTest
    @EnumSource(value = PgStatus.class, names = {"PENDING", "FAILED"})
    void returnsNoConfirmed_whenNotSuccess(PgStatus status) {
        // arrange
        startPending();

        // act & assert
        assertThat(observe(status).confirmed()).isNull();
    }
}
