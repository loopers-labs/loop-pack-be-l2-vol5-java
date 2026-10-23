package com.loopers.batch.payment;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentReconciliationRepository {
    List<PaymentRecord> findRecentTerminalPayments(LocalDateTime from, LocalDateTime until, long afterId, int limit);

    void recordReconciliation(PaymentRecord payment, String reconciliationStatus, String providerStatus,
                             String providerResponse, LocalDateTime checkedAt);

    List<PaymentRecord> findCancelProcessingPayments(long afterId, int limit);

    int markCanceled(long paymentId, String rawProviderResponse);

    record PaymentRecord(long id, String providerOrderId, String status) {
    }
}
