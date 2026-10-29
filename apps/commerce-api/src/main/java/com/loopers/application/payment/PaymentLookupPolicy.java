package com.loopers.application.payment;

import com.loopers.application.payment.port.LookupJitter;
import com.loopers.domain.payment.LookupBackoff;
import java.time.Instant;

public record PaymentLookupPolicy(LookupBackoff backoff, LookupJitter jitter, int maxAttempts, int batchSize) {
    public Instant nextAt(Instant now, int actualAttempts) {
        return now.plusMillis(jitter.chooseMillis(backoff.ceiling(actualAttempts).toMillis()));
    }
}
