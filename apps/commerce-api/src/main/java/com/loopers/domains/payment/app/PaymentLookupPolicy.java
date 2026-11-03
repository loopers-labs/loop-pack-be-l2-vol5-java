package com.loopers.domains.payment.app;

import com.loopers.domains.payment.app.port.LookupJitter;
import com.loopers.domains.payment.domain.LookupBackoff;
import java.time.Instant;

public record PaymentLookupPolicy(LookupBackoff backoff, LookupJitter jitter, int maxAttempts, int batchSize) {
    public Instant nextAt(Instant now, int actualAttempts) {
        return now.plusMillis(jitter.chooseMillis(backoff.ceiling(actualAttempts).toMillis()));
    }
}
