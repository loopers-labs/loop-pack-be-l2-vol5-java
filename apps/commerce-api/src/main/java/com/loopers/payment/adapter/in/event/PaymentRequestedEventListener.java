package com.loopers.payment.adapter.in.event;

import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.domain.PaymentRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * TX A가 commit된 뒤에만 발송을 시작한다 (ADR-W5-01). rollback되면 실행되지 않아 PG 호출이 0이다.
 * 발행은 언제나 TX A 안이라 fallbackExecution을 켜지 않는다.
 */
@RequiredArgsConstructor
@Component
public class PaymentRequestedEventListener {

    private final PaymentDispatchUseCase paymentDispatchUseCase;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PaymentRequestedEvent event) {
        paymentDispatchUseCase.dispatch(event);
    }
}
