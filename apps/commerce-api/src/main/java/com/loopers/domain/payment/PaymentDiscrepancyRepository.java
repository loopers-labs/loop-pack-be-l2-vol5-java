package com.loopers.domain.payment;

import java.time.Instant;

public interface PaymentDiscrepancyRepository {

    void record(Long paymentId, PaymentDiscrepancy discrepancy, PaymentDiscrepancy.Source source, Instant observedAt);
}
