package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionedPaymentRequestFingerprinterTest {
    private static final PaymentFingerprintInput INPUT = input(1L, 42L, 7000L, "TEST", "0000-0000-0000-0000");
    private static final String CONFIGURATION_ERROR = "Payment fingerprint key configuration is unavailable";

    @Test
    void calculatesWithTheCurrentKeyWithoutChangingTheExistingCanonicalFormat() {
        var calculator = new VersionedPaymentRequestFingerprinter(2, keys());

        var actual = calculator.calculate(INPUT);
        var previousImplementation = new HmacPaymentRequestFingerprinter(2, key(2)).calculate(INPUT);

        assertThat(actual.fingerprintVersion()).isEqualTo(1);
        assertThat(actual.keyVersion()).isEqualTo(2);
        assertThat(actual.digest().length).isEqualTo(32);
        assertThat(Arrays.equals(actual.digest(), previousImplementation.digest())).isTrue();
        assertThat(calculator.matches(INPUT, actual)).isTrue();
    }

    @Test
    void newInstancesAndDifferentMapIterationOrdersProduceTheSameResult() {
        var first = new VersionedPaymentRequestFingerprinter(2, keys());
        Map<Integer, byte[]> reversed = new LinkedHashMap<>();
        reversed.put(2, key(2));
        reversed.put(1, key(1));
        var second = new VersionedPaymentRequestFingerprinter(2, reversed);
        var stored = first.calculate(INPUT);

        assertThat(Arrays.equals(second.calculate(INPUT).digest(), stored.digest())).isTrue();
        assertThat(second.matches(INPUT, stored)).isTrue();
        assertThat(second.calculate(INPUT).keyVersion()).isEqualTo(2);
    }

    @Test
    void switchingTheCurrentKeyKeepsOldStoredFingerprintsComparableWithoutRewritingThem() {
        var beforeRotation = new VersionedPaymentRequestFingerprinter(1, keys());
        var original = beforeRotation.calculate(INPUT);
        byte[] preserved = original.digest();
        var afterRotation = new VersionedPaymentRequestFingerprinter(2, keys());

        var newlyCalculated = afterRotation.calculate(INPUT);

        assertThat(original.keyVersion()).isEqualTo(1);
        assertThat(newlyCalculated.keyVersion()).isEqualTo(2);
        assertThat(Arrays.equals(original.digest(), newlyCalculated.digest())).isFalse();
        assertThat(afterRotation.matches(INPUT, original)).isTrue();
        assertThat(afterRotation.matches(INPUT, newlyCalculated)).isTrue();
        assertThat(Arrays.equals(original.digest(), preserved)).isTrue();
        assertThat(original.keyVersion()).isEqualTo(1);
    }

    @Test
    void callerMapAndAllKeyArraysAreCopiedBeforeTheyCanBeChangedOrRemoved() {
        byte[] historicalKey = key(1);
        byte[] currentKey = key(2);
        Map<Integer, byte[]> mutable = new HashMap<>();
        mutable.put(1, historicalKey);
        mutable.put(2, currentKey);
        var calculator = new VersionedPaymentRequestFingerprinter(2, mutable);
        var historical = new HmacPaymentRequestFingerprinter(1, key(1)).calculate(INPUT);
        var current = calculator.calculate(INPUT);

        Arrays.fill(historicalKey, (byte) 0);
        Arrays.fill(currentKey, (byte) 0);
        mutable.put(2, key(9));
        mutable.put(3, key(3));
        mutable.clear();

        assertThat(calculator.matches(INPUT, historical)).isTrue();
        assertThat(calculator.matches(INPUT, current)).isTrue();
        assertThat(Arrays.equals(calculator.calculate(INPUT).digest(), current.digest())).isTrue();
        assertThat(calculator.calculate(INPUT).keyVersion()).isEqualTo(2);
    }

    @ParameterizedTest(name = "changed semantic field case {index}")
    @MethodSource("changedInputs")
    void changedInputDoesNotMatchEitherHistoricalOrCurrentStoredFingerprint(PaymentFingerprintInput changed) {
        var calculator = new VersionedPaymentRequestFingerprinter(2, keys());
        var historical = new HmacPaymentRequestFingerprinter(1, key(1)).calculate(INPUT);
        var current = calculator.calculate(INPUT);

        assertThat(calculator.matches(changed, historical)).isFalse();
        assertThat(calculator.matches(changed, current)).isFalse();
        assertThat(calculator.matches(INPUT, historical)).isTrue();
        assertThat(calculator.matches(INPUT, current)).isTrue();
    }

    @ParameterizedTest(name = "unavailable stored comparison material case {index}")
    @MethodSource("unsupportedStoredValues")
    void missingStoredValueOrUnknownVersionsAreUnavailableRatherThanContentMismatch(PaymentRequestFingerprint stored) {
        var calculator = new VersionedPaymentRequestFingerprinter(2, keys());

        assertThatThrownBy(() -> calculator.matches(INPUT, stored))
            .isInstanceOf(PaymentFingerprintUnavailableException.class);
    }

    @Test
    void selectingAKnownDifferentKeyVersionUsesThatKeyAndDoesNotTryAllKeysUntilOneMatches() {
        var calculator = new VersionedPaymentRequestFingerprinter(2, keys());
        var first = new HmacPaymentRequestFingerprinter(1, key(1)).calculate(INPUT);
        var second = calculator.calculate(INPUT);

        assertThat(calculator.matches(INPUT, first)).isTrue();
        assertThat(calculator.matches(INPUT, second)).isTrue();
        assertThat(calculator.matches(INPUT, new PaymentRequestFingerprint(1, 2, first.digest()))).isFalse();
        assertThat(calculator.matches(INPUT, new PaymentRequestFingerprint(1, 1, second.digest()))).isFalse();
    }

    @Test
    void removingAHistoricalKeyMakesThatVersionUnavailableInsteadOfFallingBackToCurrentKey() {
        var stored = new VersionedPaymentRequestFingerprinter(1, keys()).calculate(INPUT);
        var withoutHistoricalKey = new VersionedPaymentRequestFingerprinter(2, Map.of(2, key(2)));

        assertThatThrownBy(() -> withoutHistoricalKey.matches(INPUT, stored))
            .isInstanceOf(PaymentFingerprintUnavailableException.class);
    }

    @Test
    void aDifferentDeploymentSecretWithTheSameVersionCannotBeDetectedAsAConfigurationMismatch() {
        var firstDeployment = new VersionedPaymentRequestFingerprinter(2, Map.of(2, key(2)));
        var incorrectlyConfiguredDeployment = new VersionedPaymentRequestFingerprinter(2, Map.of(2, key(9)));
        var stored = firstDeployment.calculate(INPUT);

        // 버전 숫자만으로 다른 배포의 키 원문 일치를 증명할 수 없다. 운영 설정 일관성이 별도로 필요하다.
        assertThat(incorrectlyConfiguredDeployment.matches(INPUT, stored)).isFalse();
        assertThat(firstDeployment.matches(INPUT, stored)).isTrue();
    }

    @ParameterizedTest(name = "invalid key ring configuration case {index}")
    @MethodSource("invalidConfigurations")
    void rejectsAnInvalidCurrentVersionOrAnyInvalidEntryWithOneSafeMessage(int currentVersion, Map<Integer, byte[]> values) {
        assertThatThrownBy(() -> new VersionedPaymentRequestFingerprinter(currentVersion, values))
            .isInstanceOf(PaymentFingerprintUnavailableException.class)
            .hasMessage(CONFIGURATION_ERROR);
    }

    @ParameterizedTest(name = "supported key byte length case {index}")
    @ValueSource(ints = {32, 33, 64, 128})
    void acceptsAtLeast32ByteKeysWithoutChangingTheUnderlyingHmacMeaning(int length) {
        byte[] secret = new byte[length];
        Arrays.fill(secret, (byte) 0x31);
        var calculator = new VersionedPaymentRequestFingerprinter(7, Map.of(7, secret));
        var expected = new HmacPaymentRequestFingerprinter(7, secret).calculate(INPUT);

        assertThat(Arrays.equals(calculator.calculate(INPUT).digest(), expected.digest())).isTrue();
        assertThat(calculator.matches(INPUT, expected)).isTrue();
    }

    @Test
    void missingInputPreservesTheExistingInvalidInputBoundary() {
        var calculator = new VersionedPaymentRequestFingerprinter(2, keys());
        var stored = calculator.calculate(INPUT);

        assertThatThrownBy(() -> calculator.calculate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.matches(null, stored)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void simultaneousCurrentCalculationsAndHistoricalComparisonsDoNotShareMutableMacState() throws Exception {
        var calculator = new VersionedPaymentRequestFingerprinter(2, keys());
        var pool = Executors.newFixedThreadPool(4);
        try {
            var work = new ArrayList<Callable<Boolean>>();
            for (int index = 1; index <= 32; index++) {
                var differentOrder = input(1L, index, 7000L, "TEST", "0000-0000-0000-0000");
                var historical = new HmacPaymentRequestFingerprinter(1, key(1)).calculate(differentOrder);
                var current = new HmacPaymentRequestFingerprinter(2, key(2)).calculate(differentOrder);
                work.add(() -> calculator.matches(differentOrder, historical));
                work.add(() -> Arrays.equals(calculator.calculate(differentOrder).digest(), current.digest()));
            }
            for (var result : pool.invokeAll(work, 5, TimeUnit.SECONDS)) {
                assertThat(result.isCancelled()).isFalse();
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void textRepresentationNeverIncludesTheKeyRingOrInput() {
        var calculator = new VersionedPaymentRequestFingerprinter(2, keys());

        assertThat(calculator.toString()).isEqualTo("VersionedPaymentRequestFingerprinter[REDACTED]");
        assertThat(calculator.calculate(INPUT).toString()).isEqualTo("PaymentRequestFingerprint[REDACTED]");
    }

    private static Map<Integer, byte[]> keys() {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        result.put(1, key(1));
        result.put(2, key(2));
        return result;
    }

    private static byte[] key(int marker) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) marker);
        return result;
    }

    private static Map<Integer, byte[]> oneKey(Integer version, byte[] secret) {
        Map<Integer, byte[]> result = new HashMap<>();
        result.put(version, secret);
        return result;
    }

    private static Map<Integer, byte[]> invalidHistoricalEntry(Integer version, byte[] secret) {
        Map<Integer, byte[]> result = oneKey(1, key(1));
        result.put(version, secret);
        return result;
    }

    private static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
            Arguments.of(0, keys()),
            Arguments.of(-1, keys()),
            Arguments.of(1, null),
            Arguments.of(1, Map.of()),
            Arguments.of(2, oneKey(1, key(1))),
            Arguments.of(1, invalidHistoricalEntry(0, key(2))),
            Arguments.of(1, invalidHistoricalEntry(-1, key(2))),
            Arguments.of(1, invalidHistoricalEntry(null, key(2))),
            Arguments.of(1, oneKey(1, null)),
            Arguments.of(1, oneKey(1, new byte[0])),
            Arguments.of(1, oneKey(1, new byte[31])),
            Arguments.of(1, invalidHistoricalEntry(2, null)),
            Arguments.of(1, invalidHistoricalEntry(2, new byte[0])),
            Arguments.of(1, invalidHistoricalEntry(2, new byte[31]))
        );
    }

    private static Stream<Arguments> unsupportedStoredValues() {
        byte[] digest = new HmacPaymentRequestFingerprinter(1, key(1)).calculate(INPUT).digest();
        return Stream.of(
            Arguments.of((Object) null),
            Arguments.of(new PaymentRequestFingerprint(2, 1, digest)),
            Arguments.of(new PaymentRequestFingerprint(1, 3, digest)),
            Arguments.of(new PaymentRequestFingerprint(2, 3, digest))
        );
    }

    private static Stream<PaymentFingerprintInput> changedInputs() {
        return Stream.of(
            input(2L, 42L, 7000L, "TEST", "0000-0000-0000-0000"),
            input(1L, 43L, 7000L, "TEST", "0000-0000-0000-0000"),
            input(1L, 42L, 7001L, "TEST", "0000-0000-0000-0000"),
            input(1L, 42L, 7000L, "OTHER", "0000-0000-0000-0000"),
            input(1L, 42L, 7000L, "TEST", "0000-0000-0000-0001"),
            input(1L, 42L, 7000L, "TEST", "0000000000000000"),
            input(1L, 42L, 7000L, "TEST", " 0000-0000-0000-0000 "),
            input(1L, 42L, 7000L, "test", "0000-0000-0000-0000")
        );
    }

    private static PaymentFingerprintInput input(long user, long order, long amount, String type, String card) {
        return new PaymentFingerprintInput(user, order, amount, type, card);
    }
}
