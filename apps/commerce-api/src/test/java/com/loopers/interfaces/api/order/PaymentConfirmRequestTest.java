package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentConfirmRequestTest {
    private static final String KEY = "12345678-abcd-4abc-8abc-123456789abc";
    private static ObjectMapper mapper;

    @BeforeAll
    static void configureActualJackson() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(ClassUtils.resolveClassName("com.loopers.config.jackson.JacksonConfig", null))
            .run(context -> mapper = context.getBean(ObjectMapper.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {KEY, "12345678-ABCD-4ABC-8ABC-123456789ABC"})
    void parsesCanonicalUuidWithoutChangingCardInput(String key) {
        ObjectNode body = validBody().put("paymentAttemptKey", key)
            .put("cardType", " TEST ").put("cardNo", " 0000-0000-0000-0000 ");

        PaymentConfirmRequest request = PaymentConfirmRequest.from(body);

        assertThat(request.paymentAttemptKey()).isEqualTo(UUID.fromString(KEY));
        assertThat(request.cardType()).isEqualTo(" TEST ");
        assertThat(request.cardNo()).isEqualTo(" 0000-0000-0000-0000 ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-0000-0000-0000", "TEST-ALTERNATIVE-0002", "0000000000000000"})
    void doesNotInventSingleNumberAllowlistOrLuhnRules(String cardNo) {
        PaymentConfirmRequest request = PaymentConfirmRequest.from(validBody().put("cardNo", cardNo));

        assertThat(request.cardNo()).isEqualTo(cardNo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"card\"", "42", "true", "{}"})
    void rejectsNonObjectOrEmptyBody(String json) throws Exception {
        JsonNode body = mapper.readTree(json);

        assertThatThrownBy(() -> PaymentConfirmRequest.from(body)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsAbsentBody() {
        assertThatThrownBy(() -> PaymentConfirmRequest.from(null)).isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"paymentAttemptKey", "cardType", "cardNo"})
    void rejectsMissingOrNullField(String field) {
        ObjectNode missing = validBody();
        missing.remove(field);
        ObjectNode nullField = validBody().putNull(field);

        assertThatThrownBy(() -> PaymentConfirmRequest.from(missing)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PaymentConfirmRequest.from(nullField)).isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @MethodSource("nonStringFields")
    void rejectsNonStringFieldWithoutCoercion(String field, String json) throws Exception {
        ObjectNode body = validBody();
        body.set(field, mapper.readTree(json));

        assertThatThrownBy(() -> PaymentConfirmRequest.from(body)).isInstanceOf(InvalidRequestException.class);
    }

    static Stream<Arguments> nonStringFields() {
        return Stream.of("paymentAttemptKey", "cardType", "cardNo")
            .flatMap(field -> Stream.of("1", "1.5", "true", "[]", "{}").map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "callbackUrl", "providerOrderId", "fingerprint", "payment"})
    void rejectsClientControlledExtraFields(String field) {
        ObjectNode body = validBody().put(field, "not-accepted");

        assertThatThrownBy(() -> PaymentConfirmRequest.from(body)).isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1-1-1-1-1", "12345678abcd4abc8abc123456789abc",
        "{12345678-abcd-4abc-8abc-123456789abc}", "12345678-abcd-4abc-8abc-123456789abg",
        " 12345678-abcd-4abc-8abc-123456789abc", "12345678-abcd-4abc-8abc-123456789abc "})
    void rejectsNonCanonicalUuid(String value) {
        assertThatThrownBy(() -> PaymentConfirmRequest.from(validBody().put("paymentAttemptKey", value)))
            .isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @MethodSource("blankCardFields")
    void rejectsBlankCardInput(String field, String value) {
        assertThatThrownBy(() -> PaymentConfirmRequest.from(validBody().put(field, value)))
            .isInstanceOf(InvalidRequestException.class);
    }

    static Stream<Arguments> blankCardFields() {
        return Stream.of("cardType", "cardNo")
            .flatMap(field -> Stream.of("", " ", "\t\r\n", "\u2003").map(value -> Arguments.of(field, value)));
    }

    @Test
    void directConstructionCannotBypassRequiredInputs() {
        UUID key = UUID.fromString(KEY);

        assertThatThrownBy(() -> new PaymentConfirmRequest(null, "TEST", "0000-0000-0000-0000"))
            .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new PaymentConfirmRequest(key, null, "0000-0000-0000-0000"))
            .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new PaymentConfirmRequest(key, "TEST", null))
            .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new PaymentConfirmRequest(key, "TEST", " "))
            .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void debugStringAndErrorsNeverIncludeRequestValues() {
        String marker = "SYNTHETIC-PRIVATE-CARD-MARKER";
        PaymentConfirmRequest request = PaymentConfirmRequest.from(validBody().put("cardNo", marker));

        assertThat(request.toString()).contains("REDACTED").doesNotContain(marker, KEY);
        assertThatThrownBy(() -> PaymentConfirmRequest.from(validBody().put("paymentAttemptKey", marker)))
            .isInstanceOf(InvalidRequestException.class).hasMessage(null).hasNoCause();
    }

    private static ObjectNode validBody() {
        return mapper.createObjectNode().put("paymentAttemptKey", KEY).put("cardType", "TEST")
            .put("cardNo", "0000-0000-0000-0000");
    }
}
