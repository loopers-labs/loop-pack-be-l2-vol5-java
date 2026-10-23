package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;

/**
 * 주문과 결제 시도의 저장 상태. checked는 이번 요청에서 PG의 관찰 결과를 받았거나 이미 종결된 결과를 반환할 때 true다.
 * 조회 실패·회로 차단·생성 응답 유실, PG에 묻지 않고 미완료 저장 상태만 반환하는 같은 키 재요청에서는 false다.
 * false는 결제 실패를 뜻하지 않으며, 종결된 저장 결과는 추가 조회 없이 true로 반환한다.
 */
public record OrderPaymentInfo(
    OrderInfo order,
    String paymentStatus,
    String providerOrderId,
    String transactionKey,
    long amount,
    boolean checked
) {
    static OrderPaymentInfo from(OrderModel order, PaymentAttempt attempt) {
        return new OrderPaymentInfo(
            OrderInfo.from(order, attempt),
            attempt.getStatus().name(),
            attempt.getProviderOrderId(),
            attempt.getTransactionKey(),
            attempt.getAmount(),
            true
        );
    }

    public OrderPaymentInfo notChecked() {
        return new OrderPaymentInfo(order, paymentStatus, providerOrderId, transactionKey, amount, false);
    }

    public boolean hasFinalPaymentResult() {
        return PaymentAttemptStatus.valueOf(paymentStatus).isTerminal();
    }

    /** 결제 종결과 주문·예약 반영을 모두 마친 경우다. TX B만 커밋된 상태는 완료가 아니다. */
    public boolean isFinished() {
        return switch (PaymentAttemptStatus.valueOf(paymentStatus)) {
            case SUCCESS -> OrderStatus.CONFIRMED.name().equals(order.status())
                && ReservationStatus.CONSUMED.name().equals(order.reservationStatus());
            case FAILED -> OrderStatus.PAYMENT_FAILED.name().equals(order.status())
                && ReservationStatus.RELEASED.name().equals(order.reservationStatus());
            default -> false;
        };
    }
}
