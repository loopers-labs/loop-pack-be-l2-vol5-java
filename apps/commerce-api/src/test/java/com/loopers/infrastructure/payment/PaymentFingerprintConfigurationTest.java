package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses synthetic systemEnvironment sources, never the developer's actual key, DB or PG. */
@ExtendWith(OutputCaptureExtension.class)
class PaymentFingerprintConfigurationTest {
    private static final String CURRENT = "PAYMENT_FINGERPRINT_CURRENT_KEY_VERSION";
    private static final String KEYS = "PAYMENT_FINGERPRINT_KEYS";
    private static final String UNAVAILABLE = "Payment fingerprint key configuration is unavailable";
    private static final String SECRET_MARKER = "SYNTHETIC-SECRET-MUST-NOT-BE-LOGGED";
    private static final PaymentFingerprintInput INPUT = new PaymentFingerprintInput(
        1L, 42L, 7000L, "TEST", "0000-0000-0000-0000");

    @Test
    void loadsOnlyEnvironmentKeysAndCreatesOneManagedPortWithoutDatabaseOrPg(CapturedOutput output) {
        runner(settings("2", bothKeys())).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PaymentRequestFingerprinter.class);
            assertThat(context).doesNotHaveBean(DataSource.class).doesNotHaveBean(PaymentGateway.class);
            var calculator = context.getBean(PaymentRequestFingerprinter.class);
            var result = calculator.calculate(INPUT);
            assertThat(result.keyVersion()).isEqualTo(2);
            assertThat(result.fingerprintVersion()).isEqualTo(1);
            assertThat(Arrays.equals(result.digest(), new HmacPaymentRequestFingerprinter(2, key(2)).calculate(INPUT).digest()))
                .isTrue();
            assertThat(calculator.matches(INPUT, result)).isTrue();
            assertThat(calculator.toString()).isEqualTo("VersionedPaymentRequestFingerprinter[REDACTED]");
        });
        assertNoSecretOutput(output);
    }

    @Test
    void recreatedContextsPreserveComparisonAndNewVersionStillReadsTheOldVersion() {
        var stored = new AtomicReference<PaymentRequestFingerprint>();
        runner(settings("1", bothKeys())).run(context ->
            stored.set(context.getBean(PaymentRequestFingerprinter.class).calculate(INPUT)));
        runner(settings("1", bothKeys())).run(context -> {
            var calculator = context.getBean(PaymentRequestFingerprinter.class);
            assertThat(calculator.matches(INPUT, stored.get())).isTrue();
            assertThat(Arrays.equals(calculator.calculate(INPUT).digest(), stored.get().digest())).isTrue();
        });
        runner(settings("2", bothKeys())).run(context -> {
            var calculator = context.getBean(PaymentRequestFingerprinter.class);
            assertThat(calculator.calculate(INPUT).keyVersion()).isEqualTo(2);
            assertThat(calculator.matches(INPUT, stored.get())).isTrue();
            assertThat(stored.get().keyVersion()).isEqualTo(1);
        });
    }

    @Test
    void missingOldKeyIsUnavailableNotADifferentRequestAndDoesNotDisableNewCalculation() {
        var old = new HmacPaymentRequestFingerprinter(1, key(1)).calculate(INPUT);
        runner(settings("2", "{\"2\":\"" + encoded(2) + "\"}")).run(context -> {
            var calculator = context.getBean(PaymentRequestFingerprinter.class);
            assertThatThrownBy(() -> calculator.matches(INPUT, old))
                .isInstanceOf(PaymentFingerprintUnavailableException.class);
            assertThat(calculator.calculate(INPUT).keyVersion()).isEqualTo(2);
        });
    }

    @Test
    void neverExpandsPlaceholdersInsideAnEnvironmentKey(CapturedOutput output) {
        runner(settings("1", "{\"1\":\"${synthetic.key}\"}"))
            .withPropertyValues("synthetic.key=" + encoded(1)).run(context ->
                assertUnavailable(context.getBean(PaymentRequestFingerprinter.class)));
        assertNoSecretOutput(output);
    }

    @Test
    void ordinarySpringPropertiesCannotSubstituteForTheChosenEnvironmentSource() {
        runner(Map.of()).withPropertyValues(CURRENT + "=1", KEYS + "=" + bothKeys())
            .run(context -> assertUnavailable(context.getBean(PaymentRequestFingerprinter.class)));
    }

    @Test
    void absentEnvironmentSourceDoesNotReadAnotherPropertySourceOrStopContextStartup() {
        runner(Map.of()).withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withPropertyValues(CURRENT + "=1", KEYS + "=" + bothKeys()).run(context -> {
                assertThat(context).hasNotFailed();
                assertUnavailable(context.getBean(PaymentRequestFingerprinter.class));
            });
    }

    @Test
    void takesOneSnapshotAtStartupAndDoesNotRotateKeysDuringAnExistingContext() {
        var values = new HashMap<String, Object>(settings("1", bothKeys()));
        runner(values).run(context -> {
            var calculator = context.getBean(PaymentRequestFingerprinter.class);
            var stored = calculator.calculate(INPUT);
            values.put(CURRENT, "2");
            values.put(KEYS, "{\"2\":\"" + encoded(2) + "\"}");
            assertThat(calculator.calculate(INPUT).keyVersion()).isEqualTo(1);
            assertThat(calculator.matches(INPUT, stored)).isTrue();
        });
        runner(values).run(context ->
            assertThat(context.getBean(PaymentRequestFingerprinter.class).calculate(INPUT).keyVersion()).isEqualTo(2));
    }

    @Test
    void unexpectedSourceReadFailureDoesNotExposeTheSourceException(CapturedOutput output) {
        runner(Map.of()).withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()) {
                @Override
                public Object getProperty(String name) {
                    if (CURRENT.equals(name) || KEYS.equals(name)) {
                        throw new IllegalArgumentException(SECRET_MARKER);
                    }
                    return super.getProperty(name);
                }
            })).run(context -> {
                assertThat(context).hasNotFailed();
                assertUnavailable(context.getBean(PaymentRequestFingerprinter.class));
            });
        assertNoSecretOutput(output);
    }

    @ParameterizedTest(name = "invalid key configuration {index}")
    @MethodSource("invalidSettings")
    void invalidSettingsDisableOnlyFingerprintOperationsWithoutLeakingValues(Map<String, Object> settings,
                                                                            CapturedOutput output) {
        runner(settings).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PaymentRequestFingerprinter.class);
            assertThat(context).doesNotHaveBean(DataSource.class).doesNotHaveBean(PaymentGateway.class);
            var calculator = context.getBean(PaymentRequestFingerprinter.class);
            assertUnavailable(calculator);
            assertThat(calculator.toString()).isEqualTo("PaymentRequestFingerprinter[UNAVAILABLE]");
        });
        assertNoSecretOutput(output);
    }

    private static void assertUnavailable(PaymentRequestFingerprinter calculator) {
        var stored = new HmacPaymentRequestFingerprinter(1, key(1)).calculate(INPUT);
        assertThatThrownBy(() -> calculator.calculate(INPUT))
            .isInstanceOf(PaymentFingerprintUnavailableException.class).hasMessage(UNAVAILABLE).hasNoCause();
        assertThatThrownBy(() -> calculator.matches(INPUT, stored))
            .isInstanceOf(PaymentFingerprintUnavailableException.class).hasMessage(UNAVAILABLE).hasNoCause();
    }

    private static void assertNoSecretOutput(CapturedOutput output) {
        for (String sensitive : new String[] {encoded(1), encoded(2), bothKeys(), SECRET_MARKER, INPUT.cardNo()}) {
            assertThat(output.getAll().contains(sensitive)).isFalse();
        }
    }

    private static ApplicationContextRunner runner(Map<String, Object> values) {
        return new ApplicationContextRunner().withUserConfiguration(PaymentFingerprintConfiguration.class)
            .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, values)));
    }

    private static Map<String, Object> settings(String current, String keys) {
        return Map.of(CURRENT, current, KEYS, keys);
    }

    private static String bothKeys() {
        return "{\"1\":\"" + encoded(1) + "\",\"2\":\"" + encoded(2) + "\"}";
    }

    private static byte[] key(int marker) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) marker);
        return result;
    }

    private static String encoded(int marker) {
        return Base64.getEncoder().encodeToString(key(marker));
    }

    private static Stream<Arguments> invalidSettings() {
        var missing = Stream.of(Map.<String, Object>of(), Map.<String, Object>of(CURRENT, "1"),
            Map.<String, Object>of(KEYS, bothKeys()), Map.<String, Object>of(CURRENT, 1, KEYS, bothKeys()));
        var versions = Stream.of("", "0", "-1", "+1", "01", " 1", "1 ", "1.0", "2147483648", SECRET_MARKER, "3")
            .map(value -> settings(value, bothKeys()));
        var json = Stream.of("", "null", "[]", "{}", "true", "1", "\"" + SECRET_MARKER + "\"",
                "{", bothKeys() + "{}", bothKeys() + SECRET_MARKER,
                "{\"1\":null}", "{\"1\":123}", "{\"1\":true}", "{\"1\":[]}", "{\"1\":{}}",
                "{\"1\":\"\"}", "{\"1\":\"" + SECRET_MARKER + "\"}",
                "{\"1\":\"" + Base64.getEncoder().encodeToString(new byte[31]) + "\"}",
                "{\"1\":\"" + encoded(1) + "\",\"2\":\"" + SECRET_MARKER + "\"}",
                "{\"1\":\"" + encoded(1) + "\",\"1\":\"" + encoded(2) + "\"}")
            .map(value -> settings("1", value));
        var jsonVersions = Stream.of("0", "-1", "+1", "01", " 1", "1 ", "1.0", "2147483648", SECRET_MARKER)
            .map(value -> settings("1", "{\"1\":\"" + encoded(1) + "\",\"" + value + "\":\"" + encoded(2) + "\"}"));
        return Stream.of(missing, versions, json, jsonVersions).flatMap(value -> value).map(Arguments::of);
    }
}
