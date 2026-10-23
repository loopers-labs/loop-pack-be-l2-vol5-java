package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderModelTest {

    @Test
    void createsDraftWithSnapshotTotal() {
        OrderModel order = new OrderModel(1L, List.of(
            new OrderItem(10L, "티셔츠", 2_000L, 2),
            new OrderItem(20L, "모자", 3_000L, 1)
        ));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(7_000L);
        assertThat(order.getPaidAmount()).isZero();
        assertThat(order.getItems()).hasSize(2);
    }

    @Test
    void confirmsDraftWithStoredTotal() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));

        order.confirm();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(4_000L);
    }

    @Test
    void rejectsSecondConfirmation() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));
        order.confirm();

        assertThatThrownBy(order::confirm).isInstanceOf(CoreException.class);
        assertThat(order.getPaidAmount()).isEqualTo(4_000L);
    }

    @Test
    void draftHasNoPaymentMethodOrReservation() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));

        assertThat(order.getPaymentMethod()).isNull();
        assertThat(order.getReservationStatus()).isNull();
    }

    @Test
    void pointConfirmationKeepsPointMethodWithoutReservation() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));

        order.confirm();

        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
        assertThat(order.getReservationStatus()).isNull();
    }

    @Test
    void startingPgPaymentReservesStockWithoutPaying() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));

        order.startPaymentWithReservedStock();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(order.getPaidAmount()).isZero();
    }

    @Test
    void completingPgPaymentConsumesReservation() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));
        order.startPaymentWithReservedStock();

        order.completePayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(order.getPaidAmount()).isEqualTo(4_000L);
    }

    @Test
    void failingPgPaymentReleasesReservation() {
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));
        order.startPaymentWithReservedStock();

        order.failPaymentWithReleasedStock();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(order.getPaidAmount()).isZero();
    }

    @Test
    void paymentResultNeedsPendingOrder() {
        OrderModel draft = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));
        OrderModel failed = new OrderModel(1L, List.of(new OrderItem(10L, "티셔츠", 2_000L, 2)));
        failed.startPaymentWithReservedStock();
        failed.failPaymentWithReleasedStock();

        assertThatThrownBy(draft::completePayment).isInstanceOf(CoreException.class);
        assertThatThrownBy(failed::failPaymentWithReleasedStock).isInstanceOf(CoreException.class);
        assertThatThrownBy(failed::startPaymentWithReservedStock).isInstanceOf(CoreException.class);
        assertThat(failed.getReservationStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void rejectsEmptyOrderAndInvalidItem() {
        assertThatThrownBy(() -> new OrderModel(1L, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderItem(10L, "티셔츠", 2_000L, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
