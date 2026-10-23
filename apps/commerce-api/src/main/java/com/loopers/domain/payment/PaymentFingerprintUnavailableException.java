package com.loopers.domain.payment;

import java.util.Objects;

/** Technical inability to compare, never a PG rejection or proof of different input. */
public class PaymentFingerprintUnavailableException extends IllegalStateException {
    public enum Reason {
        LEGACY_FINGERPRINT_MISSING,
        SERVER_VERIFICATION_UNAVAILABLE,
        CORRUPT_STORED_FINGERPRINT
    }

    private final Reason reason;

    public PaymentFingerprintUnavailableException(String message) {
        this(Reason.SERVER_VERIFICATION_UNAVAILABLE, message);
    }

    public PaymentFingerprintUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason getReason() {
        return reason;
    }
}
