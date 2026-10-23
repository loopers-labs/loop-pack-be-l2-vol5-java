package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCallbackRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUCCESS", "FAILED"})
    void acceptsOnlyAnUntrustedNotification(String status) throws Exception {
        var input = PaymentCallbackRequest.from(mapper.readTree("""
            {"providerOrderId":"LP-ORD-0000000042","transactionKey":"TEST-TX-0042","status":"%s"}
            """.formatted(status)));
        assertThat(input.providerOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(input.transactionKey()).isEqualTo("TEST-TX-0042");
        assertThat(input.status()).isEqualTo(status);
        assertThat(input.toString()).doesNotContain("LP-ORD-0000000042", "TEST-TX-0042");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "[]", "{}", "{\"providerOrderId\":\"123456\"}",
        "{\"providerOrderId\":\"123456\",\"transactionKey\":\"tx\",\"status\":\"UNKNOWN\"}",
        "{\"providerOrderId\":\"123456\",\"transactionKey\":\"tx\",\"status\":\"success\"}",
        "{\"providerOrderId\":\"12345\",\"transactionKey\":\"tx\",\"status\":\"SUCCESS\"}",
        "{\"providerOrderId\":\"      \",\"transactionKey\":\"tx\",\"status\":\"SUCCESS\"}",
        "{\"providerOrderId\":123456,\"transactionKey\":\"tx\",\"status\":\"SUCCESS\"}",
        "{\"providerOrderId\":\"123456\",\"transactionKey\":null,\"status\":\"SUCCESS\"}",
        "{\"providerOrderId\":\"123456\",\"transactionKey\":\" \",\"status\":\"SUCCESS\"}",
        "{\"providerOrderId\":\"123456\",\"transactionKey\":3,\"status\":\"SUCCESS\"}",
        "{\"providerOrderId\":\"123456\",\"transactionKey\":\"tx\",\"status\":\"SUCCESS\",\"amount\":200}"
    })
    void rejectsMissingExtraOrInvalidFields(String json) {
        assertThatThrownBy(() -> PaymentCallbackRequest.from(mapper.readTree(json)))
            .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void acceptsTheStorageLengthBoundaryAndRejectsLongerInput() {
        var body = mapper.createObjectNode().put("providerOrderId", "가".repeat(255))
            .put("transactionKey", "나".repeat(255)).put("status", "PENDING");
        assertThat(PaymentCallbackRequest.from(body).providerOrderId()).hasSize(255);
        body.put("providerOrderId", "가".repeat(256));
        assertThatThrownBy(() -> PaymentCallbackRequest.from(body)).isInstanceOf(InvalidRequestException.class);
        body.put("providerOrderId", "123456").put("transactionKey", "나".repeat(256));
        assertThatThrownBy(() -> PaymentCallbackRequest.from(body)).isInstanceOf(InvalidRequestException.class);
    }
}
