package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import com.loopers.domain.payment.PaymentRequestFingerprinter;

import java.util.HashMap;
import java.util.Map;

/** Selects a configured key version; does not change payment state or the canonical format. */
public final class VersionedPaymentRequestFingerprinter implements PaymentRequestFingerprinter {
    private final Map<Integer, HmacPaymentRequestFingerprinter> calculators;
    private final HmacPaymentRequestFingerprinter current;

    public VersionedPaymentRequestFingerprinter(int currentKeyVersion, Map<Integer, byte[]> keys) {
        if (currentKeyVersion <= 0 || keys == null || !keys.containsKey(currentKeyVersion)) {
            throw new PaymentFingerprintUnavailableException("Payment fingerprint key configuration is unavailable");
        }
        for (var entry : keys.entrySet()) {
            if (entry.getKey() == null || entry.getKey() <= 0 || entry.getValue() == null || entry.getValue().length < 32) {
                throw new PaymentFingerprintUnavailableException("Payment fingerprint key configuration is unavailable");
            }
        }
        var configured = new HashMap<Integer, HmacPaymentRequestFingerprinter>();
        keys.forEach((version, secret) -> configured.put(version, new HmacPaymentRequestFingerprinter(version, secret)));
        this.calculators = Map.copyOf(configured);
        this.current = calculators.get(currentKeyVersion);
    }

    @Override
    public PaymentRequestFingerprint calculate(PaymentFingerprintInput input) {
        return current.calculate(input);
    }

    @Override
    public boolean matches(PaymentFingerprintInput input, PaymentRequestFingerprint stored) {
        if (stored == null || !calculators.containsKey(stored.keyVersion())) {
            throw new PaymentFingerprintUnavailableException("Payment fingerprint version is unavailable");
        }
        return calculators.get(stored.keyVersion()).matches(input, stored);
    }

    @Override
    public String toString() {
        return "VersionedPaymentRequestFingerprinter[REDACTED]";
    }
}
