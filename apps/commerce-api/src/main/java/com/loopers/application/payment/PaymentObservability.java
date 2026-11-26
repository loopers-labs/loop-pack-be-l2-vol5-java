package com.loopers.application.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
class PaymentObservability {

    private final PaymentMetrics paymentMetrics;
    private final PaymentLog paymentLog;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PaymentStarted started) {
        paymentMetrics.started();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PaymentTransitioned transition) {
        paymentMetrics.observed(transition.from(), transition.to());
        if (!transition.changed()) {
            return;
        }
        if (transition.settled()) {
            paymentMetrics.settled(transition.to(), transition.sinceRequested());
        }
        if (transition.origin().isLookup()) {
            paymentLog.changed(transition);
        }
    }
}
