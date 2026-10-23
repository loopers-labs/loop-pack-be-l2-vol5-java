package com.loopers.infrastructure.payment.support;

import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.infrastructure.payment.VersionedPaymentRequestFingerprinter;

import java.util.Arrays;
import java.util.Map;

/** TestBean factory: replaces the environment-backed bean before its creation with synthetic keys only. */
public final class PaymentFingerprintTestConfiguration {
    private PaymentFingerprintTestConfiguration() {
    }

    public static PaymentRequestFingerprinter create() {
        byte[] syntheticKey = new byte[32];
        Arrays.fill(syntheticKey, (byte) 0x0b);
        try {
            return new VersionedPaymentRequestFingerprinter(7, Map.of(7, syntheticKey));
        } finally {
            Arrays.fill(syntheticKey, (byte) 0);
        }
    }
}
