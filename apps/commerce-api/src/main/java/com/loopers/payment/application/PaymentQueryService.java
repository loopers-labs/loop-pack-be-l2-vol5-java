package com.loopers.payment.application;

import com.loopers.order.application.port.out.OrderPort;
import com.loopers.order.domain.OrderModel;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.domain.Payment;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객의 주문 결제 상태 조회 (W5 plan.md 8). 저장된 상태만 읽고 PG를 부르지 않는다.
 */
@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final OrderPort orderPort;
    private final PaymentPort paymentPort;

    /**
     * ORD-06과 같이 없거나 남의 주문이면 404. 결제 시도가 없는 주문도 404.
     */
    public PaymentInfo getMyPayment(Long userId, Long orderId) {
        OrderModel order = orderPort.findById(orderId)
            .filter(found -> found.isOwnedBy(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 주문을 찾을 수 없습니다."));
        Payment payment = paymentPort.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도가 없습니다."));
        return PaymentInfo.of(order, payment);
    }
}
