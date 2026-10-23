package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacPaymentRequestFingerprinterTest {
    private static final String SYNTHETIC_CARD = "0000-0000-0000-0000";
    private static final PaymentFingerprintInput INPUT = input(1L, 42L, 7000L, "TEST", SYNTHETIC_CARD);
    private final HmacPaymentRequestFingerprinter fingerprinter = new HmacPaymentRequestFingerprinter(7, testKey());

    @Test
    void identicalInputHasFullStableDigestAcrossCalculatorInstances() {
        PaymentRequestFingerprint stored = fingerprinter.calculate(INPUT);

        assertThat(stored.digest().length).isEqualTo(32);
        assertThat(stored.fingerprintVersion()).isEqualTo(1);
        assertThat(stored.keyVersion()).isEqualTo(7);
        assertThat(fingerprinter.matches(INPUT, stored)).isTrue();
        assertThat(new HmacPaymentRequestFingerprinter(7, testKey()).matches(INPUT, stored)).isTrue();
    }

    @Test
    void completeV1CalculationMatchesAnIndependentOpenSslAnswer() {
        // Public synthetic fixture, calculated independently with Ruby OpenSSL (not this Java encoder).
        byte[] expected = HexFormat.of().parseHex("0cb9c0527dbfe591010f6fe99bf0f1811f0091abd836f050a92af56b496c3a0a");
        assertThat(Arrays.equals(fingerprinter.calculate(INPUT).digest(), expected)).isTrue();
    }

    @ParameterizedTest(name = "changed digest position {index}")
    @ValueSource(ints = {0, 15, 31})
    void comparesTheFullDigestIncludingTheLastByte(int index) {
        byte[] changed = fingerprinter.calculate(INPUT).digest();
        changed[index] ^= 1;
        assertThat(fingerprinter.matches(INPUT, new PaymentRequestFingerprint(1, 7, changed))).isFalse();
    }

    @ParameterizedTest(name = "changed field case {index}")
    @MethodSource("changedInputs")
    void changedSemanticFieldsDoNotMatch(PaymentFingerprintInput changed) {
        PaymentRequestFingerprint stored = fingerprinter.calculate(INPUT);

        assertThat(fingerprinter.matches(changed, stored)).isFalse();
        assertThat(fingerprinter.matches(INPUT, stored)).isTrue();
    }

    @Test
    void jsonFormattingIsNotPartOfTheFingerprint() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var first = mapper.readTree("{\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}");
        var reordered = mapper.readTree("{ \"cardNo\" : \"0000-0000-0000-0000\", \"cardType\" : \"TEST\" }");
        var original = input(1L, 42L, 7000L, first.get("cardType").asText(), first.get("cardNo").asText());
        var retry = input(1L, 42L, 7000L, reordered.get("cardType").asText(), reordered.get("cardNo").asText());

        assertThat(fingerprinter.matches(retry, fingerprinter.calculate(original))).isTrue();
    }

    @Test
    void v1EncodingIsFixedAndUsesUtf8ByteLengthsAndBigEndianNumbers() {
        var unicode = input(1L, 42L, 7000L, "테스트", "합성🙂");
        // Fixed purpose (18 bytes), version (4), three longs (8 each), UTF-8 byte lengths and bytes.
        byte[] expected = HexFormat.of().parseHex(
            "4c502d5041594d454e542d52455155455354" + "00000001"
                + "0000000000000001" + "000000000000002a" + "0000000000001b58"
                + "00000009" + "ed858cec8aa4ed8ab8"
                + "0000000a" + "ed95a9ec84b1f09f9982");

        assertThat(Arrays.equals(HmacPaymentRequestFingerprinter.canonicalBytes(unicode), expected)).isTrue();
    }

    @ParameterizedTest(name = "ambiguous concatenation case {index}")
    @MethodSource("ambiguousInputs")
    void fieldBoundariesCannotCollapseDifferentInputs(PaymentFingerprintInput first,
                                                     PaymentFingerprintInput second) {
        assertThat(fingerprinter.matches(second, fingerprinter.calculate(first))).isFalse();
    }

    @ParameterizedTest(name = "malformed UTF-16 case {index}")
    @ValueSource(strings = {"\uD800", "\uD801", "\uDC00"})
    void invalidUnicodeIsNotSilentlyReplacedBeforeComparison(String malformed) {
        assertThatThrownBy(() -> fingerprinter.calculate(input(1L, 42L, 7000L, "TEST", malformed)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Payment fingerprint input cannot be encoded");
    }

    @ParameterizedTest(name = "stored amount boundary {index}")
    @ValueSource(longs = {0L, Long.MAX_VALUE})
    void preservesExistingNonNegativeLongAmountRange(long amount) {
        var boundary = input(Long.MAX_VALUE, Long.MAX_VALUE, amount, "TEST", SYNTHETIC_CARD);
        assertThat(fingerprinter.matches(boundary, fingerprinter.calculate(boundary))).isTrue();
    }

    @ParameterizedTest(name = "RFC 4231 vector {index}")
    @MethodSource("rfc4231Vectors")
    void matchesIndependentRfc4231HmacSha256Answers(byte[] key, String data, String expectedHex) {
        byte[] actual = HmacPaymentRequestFingerprinter.hmac(key, data.getBytes(StandardCharsets.US_ASCII));
        // Boolean comparison deliberately avoids printing a digest/key in failure diagnostics.
        assertThat(Arrays.equals(actual, HexFormat.of().parseHex(expectedHex))).isTrue();
    }

    @Test
    void keyBytesAreCopiedAndDifferentKeysProduceDifferentDigests() {
        byte[] key = testKey();
        var calculator = new HmacPaymentRequestFingerprinter(7, key);
        var stored = calculator.calculate(INPUT);
        Arrays.fill(key, (byte) 0x5a);

        assertThat(calculator.matches(INPUT, stored)).isTrue();
        assertThat(new HmacPaymentRequestFingerprinter(7, testKey()).matches(INPUT, stored)).isTrue();
        assertThat(new HmacPaymentRequestFingerprinter(7, key).matches(INPUT, stored)).isFalse();
    }

    @Test
    void unsupportedVersionsAreUnavailableRatherThanAContentMismatch() {
        var stored = fingerprinter.calculate(INPUT);
        var unknownFormat = new PaymentRequestFingerprint(2, 7, stored.digest());
        var unknownKey = new PaymentRequestFingerprint(1, 8, stored.digest());

        assertThatThrownBy(() -> fingerprinter.matches(INPUT, unknownFormat))
            .isInstanceOf(PaymentFingerprintUnavailableException.class);
        assertThatThrownBy(() -> fingerprinter.matches(INPUT, unknownKey))
            .isInstanceOf(PaymentFingerprintUnavailableException.class);
    }

    @ParameterizedTest(name = "unusable key configuration {index}")
    @MethodSource("unusableKeys")
    void missingOrInvalidKeyConfigurationCannotCreateACalculator(int version, byte[] key) {
        assertThatThrownBy(() -> new HmacPaymentRequestFingerprinter(version, key))
            .isInstanceOf(PaymentFingerprintUnavailableException.class)
            .hasMessage("Payment fingerprint key is unavailable");
    }

    @Test
    void missingInputOrStoredFingerprintCannotBeTreatedAsAMatch() {
        assertThatThrownBy(() -> fingerprinter.calculate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fingerprinter.matches(INPUT, null))
            .isInstanceOf(PaymentFingerprintUnavailableException.class);
    }

    @Test
    void parallelCallsDoNotShareMutableMacState() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int index = 1; index <= 64; index++) {
                var differentOrder = input(1L, index, 7000L, "TEST", SYNTHETIC_CARD);
                var expected = fingerprinter.calculate(differentOrder);
                tasks.add(() -> fingerprinter.matches(differentOrder, expected));
            }
            for (var result : pool.invokeAll(tasks, 5, TimeUnit.SECONDS)) {
                assertThat(result.isCancelled()).isFalse();
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void calculatorAndInputAndFingerprintTextAreRedacted() {
        assertThat(fingerprinter.toString()).isEqualTo("HmacPaymentRequestFingerprinter[REDACTED]");
        assertThat(INPUT.toString()).isEqualTo("PaymentFingerprintInput[REDACTED]");
        assertThat(fingerprinter.calculate(INPUT).toString()).isEqualTo("PaymentRequestFingerprint[REDACTED]");
    }

    private static PaymentFingerprintInput input(long user, long order, long amount, String type, String card) {
        return new PaymentFingerprintInput(user, order, amount, type, card);
    }

    private static byte[] testKey() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x0b);
        return key;
    }

    private static Stream<PaymentFingerprintInput> changedInputs() {
        return Stream.of(
            input(2L, 42L, 7000L, "TEST", SYNTHETIC_CARD),
            input(1L, 43L, 7000L, "TEST", SYNTHETIC_CARD),
            input(1L, 42L, 7001L, "TEST", SYNTHETIC_CARD),
            input(1L, 42L, 7000L, "TEST-OTHER", SYNTHETIC_CARD),
            input(1L, 42L, 7000L, "TEST", "0000-0000-0000-0001"),
            input(1L, 42L, 7000L, "TEST", "0000000000000000"),
            input(1L, 42L, 7000L, "TEST", " " + SYNTHETIC_CARD + " "),
            input(1L, 42L, 7000L, "test", SYNTHETIC_CARD)
        );
    }

    private static Stream<Arguments> ambiguousInputs() {
        return Stream.of(
            Arguments.of(input(1L, 23L, 7000L, "TEST", SYNTHETIC_CARD),
                input(12L, 3L, 7000L, "TEST", SYNTHETIC_CARD)),
            Arguments.of(input(1L, 42L, 7000L, "TEST", "X-0000"),
                input(1L, 42L, 7000L, "TESTX", "-0000")),
            Arguments.of(input(1L, 42L, 7000L, "TEST\u0000", "0000"),
                input(1L, 42L, 7000L, "TEST", "\u00000000"))
        );
    }

    private static Stream<Arguments> rfc4231Vectors() {
        return Stream.of(
            Arguments.of(HexFormat.of().parseHex("0b".repeat(20)), "Hi There",
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            Arguments.of("Jefe".getBytes(StandardCharsets.US_ASCII), "what do ya want for nothing?",
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            Arguments.of(HexFormat.of().parseHex("aa".repeat(131)),
                "Test Using Larger Than Block-Size Key - Hash Key First",
                "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54")
        );
    }

    private static Stream<Arguments> unusableKeys() {
        return Stream.of(Arguments.of(0, testKey()), Arguments.of(-1, testKey()),
            Arguments.of(7, null), Arguments.of(7, new byte[0]), Arguments.of(7, new byte[31]));
    }
}
