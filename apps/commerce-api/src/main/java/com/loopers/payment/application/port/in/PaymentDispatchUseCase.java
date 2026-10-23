package com.loopers.payment.application.port.in;

import com.loopers.payment.domain.PaymentRequestedEvent;

/**
 * commit된 결제 요청을 PG로 한 번 보낸다 (plan.md 5-1, ADR-W5-01·03).
 */
public interface PaymentDispatchUseCase {

    /**
     * 발송 스레드풀에 넘긴다. 풀이 가득 차 넘기지 못하면 보내지 않고 바로 실패로 닫는다 (ADR-W5-03).
     */
    void dispatch(PaymentRequestedEvent event);
}
