package com.loopers.domain.payment;

/** Internal comparison material, not an API DTO or a PG idempotency key. */
public final class PaymentRequestFingerprint {
    private final int fingerprintVersion;
    private final int keyVersion;
    private final byte[] digest;

    public PaymentRequestFingerprint(int fingerprintVersion, int keyVersion, byte[] digest) {
        if (fingerprintVersion <= 0 || keyVersion <= 0 || digest == null || digest.length != 32) {
            throw new IllegalArgumentException("Invalid payment fingerprint representation");
        }
        this.fingerprintVersion = fingerprintVersion;
        this.keyVersion = keyVersion;
        this.digest = digest.clone();
    }

    public int fingerprintVersion() {
        return fingerprintVersion;
    }

    public int keyVersion() {
        return keyVersion;
    }

    public byte[] digest() {
        return digest.clone();
    }

    @Override
    public String toString() {
        return "PaymentRequestFingerprint[REDACTED]";
    }
}
