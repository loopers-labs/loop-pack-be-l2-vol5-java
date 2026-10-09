package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 주문 애그리거트의 저장 · 조회 · 시각 결정. 상품 · 포인트와의 협력은 OrderFacadeTest 에서 확인함 (설계 4.4) */
class OrderServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-06T10:00:00+09:00[Asia/Seoul]");

    private FakeOrderRepository orderRepository;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = new FakeOrderRepository();
        orderService = new OrderService(orderRepository, Clock.fixed(NOW.toInstant(), NOW.getZone()));
    }

    private Order createDraft() {
        return orderService.create(USER_ID, List.of(new OrderLine(10L, "상품", 1_000L, 2)));
    }

    @DisplayName("주문을 만들면, 시계의 현재 시각 + 30분을 만료 시각으로 둔 DRAFT 가 저장된다. (ORD-08)")
    @Test
    void createsDraftWithExpiry() {
        Order order = createDraft();

        assertAll(
            () -> assertThat(orderRepository.findById(order.getId())).isPresent(),
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(order.getExpiresAt()).isEqualTo(NOW.plusMinutes(30))
        );
    }

    @DisplayName("확정할 주문을 찾을 때, 타인의 주문이면 ORDER_NOT_FOUND 예외가 발생한다. (ORD-06)")
    @Test
    void throwsOrderNotFound_whenOrderBelongsToOthers() {
        Order order = createDraft();

        CoreException result = assertThrows(CoreException.class, () -> orderService.getConfirmableOrder(OTHER_USER_ID, order.getId()));

        assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    @DisplayName("확정하면, 결제액과 시계의 현재 시각을 결제 시각으로 남기고 CONFIRMED 가 된다. (ORD-12)")
    @Test
    void confirmsWithClockTime() {
        Order order = orderService.getConfirmableOrder(USER_ID, createDraft().getId());

        orderService.confirm(order, 2_000L);

        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order.getPaymentAmount()).isEqualTo(2_000L),
            () -> assertThat(order.getPaidAt()).isEqualTo(NOW)
        );
    }
}
