package com.loopers.domain.order.service;

import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.enums.OrderErrorCode;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.repository.FakeOrderRepository;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 주문 애그리거트의 저장 · 조회 · 시각 결정. 협력은 OrderFacadeTest · OrderPaymentFacadeTest 에서 확인함 */
class OrderServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-06T10:00:00+09:00[Asia/Seoul]");

    private FakeOrderRepository orderRepository;
    private OrderService orderService;
    private List<Object> events;

    @BeforeEach
    void setUp() {
        orderRepository = new FakeOrderRepository();
        events = new ArrayList<>();
        orderService = new OrderService(orderRepository, Clock.fixed(NOW.toInstant(), NOW.getZone()), events::add);
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

    @DisplayName("결제를 위해 잠금으로 찾을 때, 타인의 주문이면 ORDER_NOT_FOUND 예외가 발생한다. (ORD-06)")
    @Test
    void throwsOrderNotFound_whenOrderBelongsToOthers() {
        Order order = createDraft();

        CoreException result = assertThrows(CoreException.class, () -> orderService.getMyOrderForUpdate(OTHER_USER_ID, order.getId()));

        assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    @DisplayName("결제를 시작하면 PAYMENT_PENDING 이 되고, 결과를 반영하면 결제액과 시계의 현재 시각을 결제 시각으로 남긴다. (5주차 설계 2.1)")
    @Test
    void startsAndConfirmsPaymentWithClockTime() {
        Order order = orderService.getMyOrderForUpdate(USER_ID, createDraft().getId());

        orderService.startPayment(order);
        OrderStatus started = order.getStatus();
        orderService.confirmPaid(order, 2_000L);

        assertAll(
            () -> assertThat(started).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order.getPaymentAmount()).isEqualTo(2_000L),
            () -> assertThat(order.getPaidAt()).isEqualTo(NOW)
        );
    }

    @DisplayName("결제 결과로 처음 확정하면, 저장된 확정 시각 · 품목을 담은 확정 사실을 한 번 낸다. (6주차 D-70, D-72)")
    @Test
    void publishesConfirmed_whenPaid() {
        Order order = orderService.getMyOrderForUpdate(USER_ID, createDraft().getId());
        orderService.startPayment(order);

        orderService.confirmPaid(order, 2_000L);

        assertThat(events).containsExactly(new OrderConfirmed(
            "order-confirmed:v1:" + order.getId(), order.getId(), order.getConfirmedAt(), List.of(new OrderConfirmed.Item(10L, 2))));
    }

    @DisplayName("0원 주문을 결제 없이 확정해도, 확정 사실을 한 번 낸다. (6주차 D-70)")
    @Test
    void publishesConfirmed_whenConfirmedWithoutPayment() {
        Order order = orderService.create(USER_ID, List.of(new OrderLine(10L, "0원 상품", 0L, 1)));

        orderService.confirmWithoutPayment(order);

        assertThat(events).singleElement().isEqualTo(OrderConfirmed.from(order));
    }

    @DisplayName("결제 시작 · 실패 · 이미 확정된 주문의 재반영에서는 확정 사실을 내지 않는다.")
    @Test
    void doesNotPublish_whenNotFirstConfirmation() {
        Order failed = orderService.getMyOrderForUpdate(USER_ID, createDraft().getId());
        orderService.startPayment(failed);
        orderService.failPayment(failed);
        Order confirmed = orderService.getMyOrderForUpdate(USER_ID, createDraft().getId());
        orderService.startPayment(confirmed);
        orderService.confirmPaid(confirmed, 2_000L);
        events.clear();

        assertThrows(IllegalStateException.class, () -> orderService.confirmPaid(confirmed, 2_000L));

        assertThat(events).isEmpty();
    }
}
