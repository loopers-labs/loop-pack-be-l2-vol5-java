package com.loopers.domain.payment;

/** Request content comparison only; does not authorize dispatch or apply payment effects. */
public interface PaymentRequestFingerprinter {
    PaymentRequestFingerprint calculate(PaymentFingerprintInput input);

    /** Unsupported/missing comparison material must throw, not return a content mismatch. */
    boolean matches(PaymentFingerprintInput input, PaymentRequestFingerprint stored);
}
