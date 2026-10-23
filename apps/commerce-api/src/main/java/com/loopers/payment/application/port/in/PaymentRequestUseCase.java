package com.loopers.payment.application.port.in;

/**
 * 주문 확정 = PG 결제 요청의 입구 (plan.md 4장 TX A). PG를 부르지 않고, commit 뒤 발송을 맡긴다.
 */
public interface PaymentRequestUseCase {

    /**
     * 같은 시도 키 + 같은 주문이면 새 효과 없이 현재 상태를 돌려준다. 같은 키 + 다른 주문, 다른 키로 이미 시도한 주문, DRAFT가 아닌 주문은 409.
     */
    PaymentInfo request(PaymentRequestCommand command);
}
