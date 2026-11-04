package com.loopers.domain.payment;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * PG 주문과 결제 시도의 저장 연결·상태 결합 검사(설계 3.6 TX B 검사, W7.3-2). PaymentTransactionService 에 있던 검사를 조건 그대로 옮긴 순수 검사다.
 * 최초 확정·결과 반영과 Outbox 복구가 같은 기준을 쓴다. Repository·Clock·HTTP·상태 변경이 없다.
 * 복구에만 필요한 추가 전제(CONFIRMED·SUCCESS·거래 키 존재)는 여기에 넣지 않는다. 모든 PG 흐름의 정책이 바뀌기 때문이다.
 */
public final class PgOrderConsistency {

    private PgOrderConsistency() {
    }

    /** 어긋나면 임의로 처리하지 않고 INTERNAL_ERROR 로 거절한다. */
    public static void requireConsistent(OrderModel order, PaymentModel payment, String providerAccount) {
        order.requireStoredItemsConsistent();
        boolean linked = payment.getOrderId().equals(order.getId())
            && PaymentModel.PROVIDER.equals(payment.getProvider())
            && providerAccount.equals(payment.getProviderAccount())
            && PaymentModel.providerOrderIdOf(order.getId()).equals(payment.getProviderOrderId())
            && payment.getAmount().equals(order.getTotalAmount())
            && order.getPaymentMethod() == PaymentMethod.PG
            && Long.valueOf(0L).equals(order.getPointAmount());
        // POINT 주문은 linked 에서 이미 걸러지므로 아래 확정 시각 조건은 PG 주문에만 적용된다.
        boolean statusMatches = switch (order.getStatus()) {
            case PAYMENT_PENDING -> (payment.getStatus() == PaymentStatus.UNKNOWN || payment.getStatus() == PaymentStatus.PENDING)
                && order.getPaidAmount() == null && order.getConfirmedAt() == null;
            case CONFIRMED -> payment.getStatus() == PaymentStatus.SUCCESS && payment.getAmount().equals(order.getPaidAmount())
                && OrderModel.isUtcMicros(order.getConfirmedAt());
            case PAYMENT_FAILED -> payment.getStatus() == PaymentStatus.FAILED && order.getPaidAmount() == null
                && order.getConfirmedAt() == null;
            case DRAFT -> false;
        };
        if (!linked || !statusMatches) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "주문과 결제 시도의 저장 상태가 맞지 않습니다. [orderId = "
                + order.getId() + "]");
        }
    }
}
