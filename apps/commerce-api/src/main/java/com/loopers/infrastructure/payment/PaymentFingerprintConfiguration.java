package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;

/** Key loading only: does not connect to a datasource or to the PG. */
@Configuration(proxyBeanMethods = false)
public class PaymentFingerprintConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentFingerprintConfiguration.class);
    private static final String CURRENT = "PAYMENT_FINGERPRINT_CURRENT_KEY_VERSION";
    private static final String KEYS = "PAYMENT_FINGERPRINT_KEYS";
    private static final JsonMapper KEY_JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    @Bean
    public PaymentRequestFingerprinter paymentRequestFingerprinter(ConfigurableEnvironment environment) {
        try {
            // Read the chosen source directly: no YAML/CLI fallback, placeholder expansion or binder error dump.
            var source = environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            int currentVersion = positiveVersion(requiredValue(source, CURRENT));
            return configured(currentVersion, requiredValue(source, KEYS));
        } catch (IOException | RuntimeException exception) {
            // Do not attach the exception: parser/source diagnostics may contain the entire key configuration.
            LOG.warn("Payment fingerprint key configuration is unavailable; calculation and comparison are disabled");
            return new UnavailableFingerprinter();
        }
    }

    private static PaymentRequestFingerprinter configured(int currentVersion, String json) throws IOException {
        var decoded = new HashMap<Integer, byte[]>();
        try {
            var root = KEY_JSON.readTree(json);
            if (root == null || !root.isObject()) {
                throw new PaymentFingerprintUnavailableException("Payment fingerprint key configuration is unavailable");
            }
            var entries = root.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                int version = positiveVersion(entry.getKey());
                if (!entry.getValue().isTextual()) {
                    throw new PaymentFingerprintUnavailableException("Payment fingerprint key configuration is unavailable");
                }
                decoded.put(version, Base64.getDecoder().decode(entry.getValue().textValue()));
            }
            return new VersionedPaymentRequestFingerprinter(currentVersion, decoded);
        } finally {
            // The calculator has its own copies; environment/Jackson Strings cannot be completely erased here.
            decoded.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        }
    }

    private static String requiredValue(PropertySource<?> source, String name) {
        if (source == null || !(source.getProperty(name) instanceof String value)) {
            throw new PaymentFingerprintUnavailableException("Payment fingerprint key configuration is unavailable");
        }
        return value;
    }

    private static int positiveVersion(String value) {
        if (!value.matches("[1-9][0-9]*")) {
            throw new PaymentFingerprintUnavailableException("Payment fingerprint key configuration is unavailable");
        }
        return Integer.parseInt(value);
    }

    private static final class UnavailableFingerprinter implements PaymentRequestFingerprinter {
        @Override
        public PaymentRequestFingerprint calculate(PaymentFingerprintInput input) {
            throw new PaymentFingerprintUnavailableException("Payment fingerprint key configuration is unavailable");
        }

        @Override
        public boolean matches(PaymentFingerprintInput input, PaymentRequestFingerprint stored) {
            throw new PaymentFingerprintUnavailableException("Payment fingerprint key configuration is unavailable");
        }

        @Override
        public String toString() {
            return "PaymentRequestFingerprinter[UNAVAILABLE]";
        }
    }
}
