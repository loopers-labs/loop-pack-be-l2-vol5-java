package com.loopers.domains.payment.app;

import com.loopers.domains.payment.app.port.PaymentLookupCircuitState;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundTrackingService {
    // [임시] 한 회차에 다루는 대상 수 상한이다(계획 2-2).
    public static final int BATCH_SIZE = 20;

    private final PaymentRefundCaseRepository paymentRefundCaseRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final PaymentLookupCircuitState paymentLookupCircuitState;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final RefundOutcomeRecorder refundOutcomeRecorder;

    public RefundTrackingService(PaymentRefundCaseRepository paymentRefundCaseRepository,
        PaymentGatewayRegistry paymentGatewayRegistry, PaymentLookupCircuitState paymentLookupCircuitState,
        TransactionTemplate transactionTemplate, Clock clock, RefundOutcomeRecorder refundOutcomeRecorder) {
        this.paymentRefundCaseRepository = paymentRefundCaseRepository;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
        this.paymentLookupCircuitState = paymentLookupCircuitState;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.refundOutcomeRecorder = refundOutcomeRecorder;
    }

    public void trackDueCases() {
    }
}
