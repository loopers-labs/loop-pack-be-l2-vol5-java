package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentFingerprintUnavailableException;
import com.loopers.domain.payment.PaymentRequestConflictException;
import com.loopers.domain.payment.PaymentRequestFingerprint;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.infrastructure.payment.VersionedPaymentRequestFingerprinter;
import com.loopers.interfaces.api.commerce.CommerceErrors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DB/HTTP/real keys are not used: translate internal causes and serialize the approved error envelope. */
class PaymentErrorContractTest {
    private static final String INTERNAL = "일시적인 오류가 발생했습니다.";
    private static final String UNAVAILABLE = "현재 결제 요청을 확인할 수 없습니다.";
    private static final String LEGACY = "기존 결제 요청과 비교할 정보가 없습니다. 주문 조회 또는 결제 결과 재확인을 이용해 주세요.";
    private static final ZonedDateTime START = ZonedDateTime.parse("2026-10-23T00:00:00Z");
    private static final PaymentFingerprintInput INPUT = new PaymentFingerprintInput(
        1L, 42L, 7000L, "TEST", "0000-0000-0000-0000");
    private static ObjectMapper mapper;
    private final CommerceErrors errors = new CommerceErrors(mapper);

    @BeforeAll
    static void configureActualJackson() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(ClassUtils.resolveClassName("com.loopers.config.jackson.JacksonConfig", null))
            .run(context -> mapper = context.getBean(ObjectMapper.class));
    }

    @Test
    void differentRequestHasItsOwnConflictContract() throws Exception {
        assertFailure(new PaymentRequestConflictException(), 409, "PAYMENT_REQUEST_CONFLICT",
            "기존 결제 시도와 일치하지 않는 요청입니다.");
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void legacyCompleteNullFingerprintIsUnverifiableWithoutChangingThePayment(PaymentAttemptStatus status) throws Exception {
        var attempt = legacyAttempt();
        moveTo(attempt, status);
        var failure = assertThrows(PaymentFingerprintUnavailableException.class, attempt::requireRequestFingerprint);

        assertFailure(failure, 409, "PAYMENT_REQUEST_UNVERIFIABLE", LEGACY);
        assertThat(attempt.getRequestFingerprint()).isEmpty();
        assertThat(attempt.getStatus()).isEqualTo(status);
        assertThat(attempt.getSendStartedAt()).isEqualTo(START);
        assertThat(failure).isInstanceOf(IllegalStateException.class)
            .hasMessage("Payment request fingerprint is unavailable").hasNoCause();
    }

    @ParameterizedTest
    @CsvSource(value = {
        "32,NULL,NULL", "NULL,1,NULL", "NULL,NULL,7", "32,1,NULL", "32,NULL,7", "NULL,1,7",
        "0,1,7", "31,1,7", "33,1,7", "32,0,7", "32,-1,7", "32,1,0", "32,1,-1"
    }, nullValues = "NULL")
    void corruptStoredFingerprintRemainsAnInternalError(Integer length, Integer format, Integer key) throws Exception {
        var attempt = legacyAttempt();
        attempt.startDispatch(START);
        ReflectionTestUtils.setField(attempt, "requestFingerprint", length == null ? null : new byte[length]);
        ReflectionTestUtils.setField(attempt, "fingerprintVersion", format);
        ReflectionTestUtils.setField(attempt, "keyVersion", key);
        var failure = assertThrows(PaymentFingerprintUnavailableException.class, attempt::requireRequestFingerprint);

        assertFailure(failure, 500, "INTERNAL_ERROR", INTERNAL);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.NOT_OBSERVED);
        assertThat(attempt.getSendStartedAt()).isEqualTo(START);
        assertThat(failure).isInstanceOf(IllegalStateException.class)
            .hasMessage("Payment request fingerprint is unavailable").hasNoCause();
    }

    @ParameterizedTest
    @CsvSource({"1,2", "2,1"})
    void missingHistoricalKeyAndUnsupportedFormatAreServerVerificationFailures(int format, int key) throws Exception {
        var calculator = new VersionedPaymentRequestFingerprinter(1, Map.of(1, new byte[32]));
        var stored = new PaymentRequestFingerprint(format, key, new byte[32]);
        var failure = assertThrows(PaymentFingerprintUnavailableException.class, () -> calculator.matches(INPUT, stored));

        assertFailure(failure, 503, "PAYMENT_VERIFICATION_UNAVAILABLE", UNAVAILABLE);
        assertThat(stored.fingerprintVersion()).isEqualTo(format);
        assertThat(stored.keyVersion()).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Payment fingerprint key configuration is unavailable",
        "Payment request fingerprint is unavailable",
        "SYNTHETIC-SECRET-DO-NOT-EXPOSE"
    })
    void compatibleConstructorDefaultsToServerFailureRegardlessOfMessage(String message) throws Exception {
        var failure = new PaymentFingerprintUnavailableException(message);

        assertFailure(failure, 503, "PAYMENT_VERIFICATION_UNAVAILABLE", UNAVAILABLE);
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(message).hasNoCause();
    }

    @Test
    void unrelatedStateFailureWithTheSameMessageIsNotReclassified() throws Exception {
        assertFailure(new IllegalStateException("Payment request fingerprint is unavailable"), 500, "INTERNAL_ERROR", INTERNAL);
    }

    private void assertFailure(Exception exception, int status, String code, String message) throws Exception {
        var failure = errors.translate(exception);
        assertThat(failure).isEqualTo(new CommerceErrors.Failure(status, code, message));
        var response = new MockHttpServletResponse();
        errors.write(response, failure);
        var json = mapper.readTree(response.getContentAsString());

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(json.size()).isEqualTo(1);
        assertThat(json.has("data")).isFalse();
        assertThat(json.path("meta").size()).isEqualTo(3);
        assertThat(json.path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(json.path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(json.path("meta").path("message").asText()).isEqualTo(message);
        assertThat(response.getContentAsString()).doesNotContain("0000-0000-0000-0000", "SYNTHETIC-SECRET",
            "requestFingerprint", "keyVersion", "fingerprintVersion", "stackTrace", exception.getMessage());
    }

    private static PaymentAttempt legacyAttempt() {
        return new PaymentAttempt(42L, UUID.randomUUID(), "135135", "LP-ORD-42", 7000L);
    }

    private static void moveTo(PaymentAttempt attempt, PaymentAttemptStatus status) {
        attempt.startDispatch(START);
        if (status == PaymentAttemptStatus.UNKNOWN) {
            attempt.markUnknown();
        } else if (status != PaymentAttemptStatus.NOT_OBSERVED) {
            attempt.observe(new PaymentTransaction("135135", "LP-ORD-42", "TX-42", 7000L,
                PaymentTransaction.Status.valueOf(status.name())));
        }
    }

}
