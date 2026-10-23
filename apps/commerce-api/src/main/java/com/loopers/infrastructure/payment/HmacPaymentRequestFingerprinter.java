package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import com.loopers.domain.payment.PaymentRequestFingerprinter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/** Pure calculator; key loading, Spring wiring and payment persistence are separate increments. */
public final class HmacPaymentRequestFingerprinter implements PaymentRequestFingerprinter {
    private static final int FORMAT_VERSION = 1;
    private static final byte[] PURPOSE = "LP-PAYMENT-REQUEST".getBytes(StandardCharsets.US_ASCII);
    private final int keyVersion;
    private final byte[] secret;

    public HmacPaymentRequestFingerprinter(int keyVersion, byte[] secret) {
        if (keyVersion <= 0 || secret == null || secret.length < 32) {
            throw new PaymentFingerprintUnavailableException("Payment fingerprint key is unavailable");
        }
        this.keyVersion = keyVersion;
        this.secret = secret.clone();
    }

    @Override
    public PaymentRequestFingerprint calculate(PaymentFingerprintInput input) {
        byte[] digest = digest(input);
        try {
            return new PaymentRequestFingerprint(FORMAT_VERSION, keyVersion, digest);
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    @Override
    public boolean matches(PaymentFingerprintInput input, PaymentRequestFingerprint stored) {
        if (stored == null || stored.fingerprintVersion() != FORMAT_VERSION || stored.keyVersion() != keyVersion) {
            throw new PaymentFingerprintUnavailableException("Payment fingerprint version is unavailable");
        }
        byte[] actual = digest(input);
        byte[] expected = stored.digest();
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private byte[] digest(PaymentFingerprintInput input) {
        byte[] encoded = canonicalBytes(input);
        try {
            return hmac(secret, encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    static byte[] canonicalBytes(PaymentFingerprintInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Invalid payment fingerprint input");
        }
        byte[] type = utf8(input.cardType());
        byte[] number = null;
        try {
            number = utf8(input.cardNo());
            int fixedLength = PURPOSE.length + Integer.BYTES * 3 + Long.BYTES * 3;
            int totalLength = Math.addExact(fixedLength, Math.addExact(type.length, number.length));
            return ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
                .put(PURPOSE).putInt(FORMAT_VERSION)
                .putLong(input.userId()).putLong(input.orderId()).putLong(input.storedAmount())
                .putInt(type.length).put(type).putInt(number.length).put(number).array();
        } finally {
            Arrays.fill(type, (byte) 0);
            if (number != null) {
                Arrays.fill(number, (byte) 0);
            }
        }
    }

    static byte[] hmac(byte[] key, byte[] message) {
        try {
            // Mac is stateful: each calculation owns its instance, including concurrent calls.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException exception) {
            // Do not propagate provider diagnostics that may include key or input details.
            throw new PaymentFingerprintUnavailableException("Payment fingerprint calculation is unavailable");
        }
    }

    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Payment fingerprint input cannot be encoded");
        }
    }

    @Override
    public String toString() {
        return "HmacPaymentRequestFingerprinter[REDACTED]";
    }
}
