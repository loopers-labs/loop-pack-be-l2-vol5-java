package com.loopers.application.order;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** One TX B observation. The completion callback keeps scalar evidence and performs no database work. */
@Slf4j
final class PaymentApplicationLog implements TransactionSynchronization {
    private final long orderId;
    private final UUID attemptKey;
    private final String observation;
    private String providerOrderId = "NONE";
    private String transactionKey = "NONE";
    private String decision = "NOT_EVALUATED";

    private PaymentApplicationLog(long orderId, UUID attemptKey, String observation) {
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.observation = observation;
    }

    static PaymentApplicationLog start(long orderId, UUID attemptKey, String observation, PaymentTransaction external) {
        var record = new PaymentApplicationLog(orderId, attemptKey, observation);
        if (external != null) {
            record.providerOrderId = PaymentLogId.safe(external.providerOrderId());
            record.transactionKey = PaymentLogId.safe(external.transactionKey());
        }
        log.info("payment_apply phase=STARTED observation={} orderId={} attemptKey={} providerOrderId={} transactionKey={}",
            observation, orderId, attemptKey, record.providerOrderId, record.transactionKey);
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(record);
        }
        return record;
    }

    void evaluated(PaymentAttempt attempt, PaymentObservationResult result) {
        providerOrderId = PaymentLogId.safe(attempt.getProviderOrderId());
        transactionKey = PaymentLogId.safe(attempt.getTransactionKey());
        decision = result.name();
    }

    @Override
    public void afterCompletion(int status) {
        String outcome = switch (status) {
            case STATUS_COMMITTED -> "COMMITTED";
            case STATUS_ROLLED_BACK -> "ROLLED_BACK";
            default -> "UNKNOWN";
        };
        log.info("payment_apply phase=COMPLETED observation={} orderId={} attemptKey={} providerOrderId={} transactionKey={} decision={} txOutcome={}",
            observation, orderId, attemptKey, providerOrderId, transactionKey, decision, outcome);
    }
}
