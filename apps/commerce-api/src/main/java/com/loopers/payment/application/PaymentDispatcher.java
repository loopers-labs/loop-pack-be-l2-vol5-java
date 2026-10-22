package com.loopers.payment.application;

import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.domain.PaymentRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/**
 * 결제 생성 요청을 PG로 한 번만 보낸다 (plan.md 4장·5-1, ADR-W5-01·03). 유일한 발송 경로다.
 * 발송은 {@link PaymentSender#send}가 발송 풀(격벽) 스레드에서 한다. 풀이 가득 차면 보내지 않고 그 자리에서 실패로 닫는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentDispatcher implements PaymentDispatchUseCase {

    private final PaymentSender paymentSender;
    private final PaymentResultApplier paymentResultApplier;

    /**
     * ADR-W5-03: 풀이 가득 차면 늦게 보내지 않는다. 요청 스레드에서 바로 FAILED(NOT_SENT)로 닫고 재고를 반환한다.
     */
    @Override
    public void dispatch(PaymentRequestedEvent event) {
        try {
            paymentSender.send(event);
        } catch (RejectedExecutionException e) {
            log.warn("발송 풀이 가득 차 보내지 않고 닫습니다. paymentId={}", event.paymentId());
            paymentResultApplier.closeUnclaimed(event.paymentId());
        }
    }
}
