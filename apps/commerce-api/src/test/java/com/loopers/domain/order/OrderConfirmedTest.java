package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("OrderConfirmed 는 PG 확정 주문의 저장 사실을 정규화해 복사한 불변 값이다.")
class OrderConfirmedTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC);
    private static final long ORDER_ID = 42L;

    /** 상품 8·7·8 순서의 원본 품목. 정상 주문 생성은 중복을 합치지만 사건 값의 정규화를 확인하려고 그대로 둔다. */
    private OrderModel confirmedPgOrder() {
        OrderModel order = OrderModel.draft(1L, List.of(
            OrderItemModel.of(8L, 1L, Money.of(1_000L)),
            OrderItemModel.of(7L, 2L, Money.of(500L)),
            OrderItemModel.of(8L, 3L, Money.of(1_000L))));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.startPgPayment();
        order.confirmPg(order.getTotalAmount(), CONFIRMED_AT);
        return order;
    }

    private static void setQuantity(OrderModel order, int index, long quantity) {
        ReflectionTestUtils.setField(order.getItems().get(index), "quantity", quantity);
    }

    private static void assertIntegrityError(OrderModel order) {
        assertThatThrownBy(() -> OrderConfirmed.from(order))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
    }

    @DisplayName("주문 ID·저장된 확정 시각을 복사하고 품목을 상품별로 합산해 ID 오름차순으로 둔다.")
    @Test
    void copiesNormalizedFacts() {
        OrderConfirmed event = OrderConfirmed.from(confirmedPgOrder());

        assertAll(
            () -> assertThat(event.orderId()).isEqualTo(ORDER_ID),
            () -> assertThat(event.confirmedAt()).isEqualTo(CONFIRMED_AT),
            () -> assertThat(event.items()).containsExactly(new OrderConfirmed.Item(7L, 2L), new OrderConfirmed.Item(8L, 4L))
        );
    }

    @DisplayName("eventId 는 주문 ID 로만 정해져 같은 주문이면 항상 같다.")
    @Test
    void derivesStableEventId() {
        OrderModel order = confirmedPgOrder();

        assertAll(
            () -> assertThat(OrderConfirmed.from(order).eventId()).isEqualTo("order-confirmed:42"),
            () -> assertThat(OrderConfirmed.from(order).eventId()).isEqualTo(OrderConfirmed.from(order).eventId()),
            () -> assertThat(new OrderConfirmed(ORDER_ID, CONFIRMED_AT.plusDays(1), List.of(new OrderConfirmed.Item(1L, 1L)))
                .eventId()).as("시각·품목을 섞지 않는다").isEqualTo("order-confirmed:42")
        );
    }

    @DisplayName("품목 목록은 수정할 수 없고, 만든 뒤 Entity 품목이 바뀌어도 사건 값은 그대로다.")
    @Test
    void isImmutableAndDetachedFromEntity() {
        OrderModel order = confirmedPgOrder();
        OrderConfirmed event = OrderConfirmed.from(order);

        setQuantity(order, 1, 99L);

        assertAll(
            () -> assertThatThrownBy(() -> event.items().add(new OrderConfirmed.Item(9L, 1L)))
                .isInstanceOf(UnsupportedOperationException.class),
            () -> assertThat(event.items()).containsExactly(new OrderConfirmed.Item(7L, 2L), new OrderConfirmed.Item(8L, 4L))
        );
    }

    @DisplayName("생성자에 넘긴 가변 목록을 나중에 바꿔도 사건 품목은 바뀌지 않는다.")
    @Test
    void copiesGivenItems() {
        List<OrderConfirmed.Item> source = new ArrayList<>(List.of(new OrderConfirmed.Item(1L, 1L)));
        OrderConfirmed event = new OrderConfirmed(ORDER_ID, CONFIRMED_AT, source);

        source.add(new OrderConfirmed.Item(2L, 1L));

        assertThat(event.items()).containsExactly(new OrderConfirmed.Item(1L, 1L));
    }

    @DisplayName("같은 상품의 원본 수량 -1·3 은 합계 2 로 가리지 않고 거절한다.")
    @Test
    void rejectsNonPositiveSourceQuantityBeforeSumming() {
        OrderModel order = confirmedPgOrder();
        setQuantity(order, 0, -1L);

        assertIntegrityError(order);
    }

    @DisplayName("원본 수량 0, 상품 ID 누락·0 이하도 거절한다.")
    @Test
    void rejectsInvalidSourceItems() {
        OrderModel zeroQuantity = confirmedPgOrder();
        setQuantity(zeroQuantity, 1, 0L);
        OrderModel missingProduct = confirmedPgOrder();
        ReflectionTestUtils.setField(missingProduct.getItems().get(1), "productId", null);
        OrderModel zeroProduct = confirmedPgOrder();
        ReflectionTestUtils.setField(zeroProduct.getItems().get(1), "productId", 0L);

        assertAll(
            () -> assertIntegrityError(zeroQuantity),
            () -> assertIntegrityError(missingProduct),
            () -> assertIntegrityError(zeroProduct)
        );
    }

    @DisplayName("상품별 수량 합이 long 범위를 넘으면 거절한다.")
    @Test
    void rejectsQuantityOverflow() {
        OrderModel order = confirmedPgOrder();
        setQuantity(order, 0, Long.MAX_VALUE);

        assertIntegrityError(order);
    }

    @DisplayName("저장 품목이 비어 있으면 내부 오류다.")
    @Test
    void rejectsEmptyItems() {
        OrderModel order = confirmedPgOrder();
        ReflectionTestUtils.setField(order, "items", new ArrayList<OrderItemModel>());

        assertIntegrityError(order);
    }

    @DisplayName("DRAFT·PG 대기·PG 실패·POINT 확정 주문으로는 만들 수 없다.")
    @Test
    void rejectsOrdersOtherThanConfirmedPg() {
        OrderModel draft = OrderModel.draft(1L, List.of(OrderItemModel.of(7L, 1L, Money.of(1_000L))));
        ReflectionTestUtils.setField(draft, "id", ORDER_ID);
        OrderModel pending = OrderModel.draft(1L, List.of(OrderItemModel.of(7L, 1L, Money.of(1_000L))));
        ReflectionTestUtils.setField(pending, "id", ORDER_ID);
        pending.startPgPayment();
        OrderModel failed = OrderModel.draft(1L, List.of(OrderItemModel.of(7L, 1L, Money.of(1_000L))));
        ReflectionTestUtils.setField(failed, "id", ORDER_ID);
        failed.startPgPayment();
        failed.failPg();
        OrderModel point = OrderModel.draft(1L, List.of(OrderItemModel.of(7L, 1L, Money.of(1_000L))));
        ReflectionTestUtils.setField(point, "id", ORDER_ID);
        point.confirmWithPoints(1_000L);

        assertAll(
            () -> assertIntegrityError(draft),
            () -> assertIntegrityError(pending),
            () -> assertIntegrityError(failed),
            () -> assertIntegrityError(point)
        );
    }

    @DisplayName("저장된 확정 시각이 없거나 UTC 마이크로초가 아니면 거절한다.")
    @Test
    void rejectsInvalidStoredConfirmedAt() {
        OrderModel missing = confirmedPgOrder();
        ReflectionTestUtils.setField(missing, "confirmedAt", null);
        OrderModel seoul = confirmedPgOrder();
        ReflectionTestUtils.setField(seoul, "confirmedAt", CONFIRMED_AT.withZoneSameInstant(ZoneId.of("Asia/Seoul")));
        OrderModel nanos = confirmedPgOrder();
        ReflectionTestUtils.setField(nanos, "confirmedAt", CONFIRMED_AT.withNano(123_456_789));

        assertAll(
            () -> assertIntegrityError(missing),
            () -> assertIntegrityError(seoul),
            () -> assertIntegrityError(nanos)
        );
    }

    @DisplayName("저장되지 않은 주문 ID(0 이하)는 내부 오류다.")
    @Test
    void rejectsNonPositiveOrderId() {
        OrderModel order = confirmedPgOrder();
        ReflectionTestUtils.setField(order, "id", 0L);

        assertIntegrityError(order);
    }

    @DisplayName("생성자로 직접 만들어도 양수 ID·UTC 마이크로초 시각·양수 품목이 있어야 한다.")
    @Test
    void constructorKeepsInvariants() {
        List<OrderConfirmed.Item> items = List.of(new OrderConfirmed.Item(1L, 1L));

        assertAll(
            () -> assertThatThrownBy(() -> new OrderConfirmed(0L, CONFIRMED_AT, items)).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> new OrderConfirmed(ORDER_ID, null, items)).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> new OrderConfirmed(ORDER_ID, CONFIRMED_AT.withNano(1), items))
                .isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> new OrderConfirmed(ORDER_ID, CONFIRMED_AT, List.of())).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> new OrderConfirmed.Item(0L, 1L)).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> new OrderConfirmed.Item(1L, -1L)).isInstanceOf(CoreException.class)
        );
    }
}
