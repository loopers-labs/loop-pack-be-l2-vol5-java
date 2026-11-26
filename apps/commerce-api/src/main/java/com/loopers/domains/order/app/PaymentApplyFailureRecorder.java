package com.loopers.domains.order.app;

import com.loopers.domains.order.app.port.PaymentApplyFailureCounter;
import com.loopers.domains.order.app.port.PaymentApplyFailureCounter.Source;
import com.loopers.domains.payment.app.port.PaymentAnomalyRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PaymentApplyFailureRecorder {
    private final PaymentAnomalyRepository paymentAnomalyRepository;
    private final PaymentApplyFailureCounter paymentApplyFailureCounter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PaymentApplyFailureRecorder(PaymentAnomalyRepository paymentAnomalyRepository,
        PaymentApplyFailureCounter paymentApplyFailureCounter, TransactionTemplate transactionTemplate, Clock clock) {
        this.paymentAnomalyRepository = paymentAnomalyRepository;
        this.paymentApplyFailureCounter = paymentApplyFailureCounter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public void record(long orderId, Source source, RuntimeException failure) {
    }
}
