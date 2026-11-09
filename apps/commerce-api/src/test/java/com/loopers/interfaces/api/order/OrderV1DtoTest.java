package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.application.order.PaymentInfo;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttemptStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 결과를 기다리거나 모르는 주문(HTTP 202)의 응답이 "상태 + 조회 경로"를 함께 알리는지 확인한다 (과제 3절).
 * 조회 경로는 둘이다. 저장된 상태를 읽는 주문 조회(GET)와, PG의 기존 거래를 다시 확인하는 reconcile(POST).
 */
class OrderV1DtoTest {

    private static OrderInfo orderInfo(OrderStatus status, PaymentMethod method, PaymentInfo payment) {
        return new OrderInfo(42L, status, List.of(new OrderItemInfo(1L, 2, 7_000L, 14_000L)), 14_000L, null, method, payment);
    }

    private static PaymentInfo payment(PaymentAttemptStatus status) {
        return new PaymentInfo(status, null, null, null, false);
    }

    @DisplayName("결제 결과를 기다리거나 모르는 주문은, 주문 조회 경로와 reconcile 경로를 함께 알린다.")
    @Test
    void showsBothPaths_whenPaymentIsPending() {
        // act
        OrderV1Dto.OrderResponse waiting = OrderV1Dto.OrderResponse.from(
            orderInfo(OrderStatus.PAYMENT_PENDING, PaymentMethod.PG, payment(PaymentAttemptStatus.PENDING)));
        OrderV1Dto.OrderResponse unknown = OrderV1Dto.OrderResponse.from(
            orderInfo(OrderStatus.PAYMENT_PENDING, PaymentMethod.PG, payment(PaymentAttemptStatus.UNKNOWN)));

        // assert
        assertThat(waiting.orderPath()).isEqualTo("/api/v1/orders/42");
        assertThat(waiting.reconcilePath()).isEqualTo("/api/v1/orders/42/payment/reconcile");
        assertThat(unknown.orderPath()).isEqualTo("/api/v1/orders/42");
        assertThat(unknown.reconcilePath()).isEqualTo("/api/v1/orders/42/payment/reconcile");
        assertThat(waiting.payment().status()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(unknown.payment().status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
    }

    @DisplayName("결제가 끝났거나 아직 시작하지 않은 주문은, 조회 경로를 알리지 않는다.")
    @Test
    void hidesPaths_whenOrderIsNotWaitingForPayment() {
        // act
        OrderV1Dto.OrderResponse draft = OrderV1Dto.OrderResponse.from(orderInfo(OrderStatus.DRAFT, null, null));
        OrderV1Dto.OrderResponse confirmed = OrderV1Dto.OrderResponse.from(
            orderInfo(OrderStatus.CONFIRMED, PaymentMethod.PG, payment(PaymentAttemptStatus.SUCCESS)));
        OrderV1Dto.OrderResponse failed = OrderV1Dto.OrderResponse.from(
            orderInfo(OrderStatus.PAYMENT_FAILED, PaymentMethod.PG, payment(PaymentAttemptStatus.FAILED)));
        OrderV1Dto.OrderResponse past = OrderV1Dto.OrderResponse.from(orderInfo(OrderStatus.CONFIRMED, PaymentMethod.POINT, null));

        // assert
        assertThat(List.of(draft, confirmed, failed, past)).allSatisfy(response -> {
            assertThat(response.orderPath()).isNull();
            assertThat(response.reconcilePath()).isNull();
        });
    }
}
