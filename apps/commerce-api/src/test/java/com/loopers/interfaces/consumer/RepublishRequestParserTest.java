package com.loopers.interfaces.consumer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

// commerce-batch의 명령 계약 테스트와 같은 fixture(modules/kafka testFixtures)를 쓴다.
class RepublishRequestParserTest {
    private final RepublishRequestParser parser = new RepublishRequestParser();

    @Test
    @DisplayName("공유 계약 fixture(주문 42)를 key 42로 읽으면 주문 42 재발행 명령이다")
    void parsesSharedFixture() throws IOException {
        try (InputStream input = new ClassPathResource("contracts/republish-order-confirmed-v1.json").getInputStream()) {
            assertThat(parser.parse("42", input.readAllBytes())).isEqualTo(new RepublishRequestParseResult.Parsed(42));
        }
    }

    @Test
    @DisplayName("모르는 필드가 더 있어도 재발행 명령으로 읽는다")
    void ignoresUnknownFields() {
        assertThat(parse("42", "{\"commandType\":\"RepublishOrderConfirmed\",\"schemaVersion\":1,\"orderId\":42,"
            + "\"requestedAt\":\"2026-10-29T01:10:00Z\",\"source\":\"batch\"}"))
            .isEqualTo(new RepublishRequestParseResult.Parsed(42));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"commandType\":\"RepublishOrderConfirmed\",\"schemaVersion\":2,\"orderId\":42,\"requestedAt\":\"2026-10-29T01:10:00Z\"}",
        "{\"commandType\":\"RepublishOrderCancelled\",\"schemaVersion\":1,\"orderId\":42,\"requestedAt\":\"2026-10-29T01:10:00Z\"}"
    })
    @DisplayName("모르는 명령 버전·종류는 처리할 수 없는 명령(멈춤 대상)이다")
    void unknownVersionOrTypeIsUnsupported(String json) {
        assertThat(parse("42", json)).isInstanceOf(RepublishRequestParseResult.Unsupported.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"commandType\":\"RepublishOrderConfirmed\",",
        "{\"commandType\":\"RepublishOrderConfirmed\",\"schemaVersion\":1,\"requestedAt\":\"2026-10-29T01:10:00Z\"}",
        "{\"commandType\":\"RepublishOrderConfirmed\",\"schemaVersion\":1,\"orderId\":\"x\",\"requestedAt\":\"2026-10-29T01:10:00Z\"}",
        "{\"commandType\":\"RepublishOrderConfirmed\",\"schemaVersion\":1,\"orderId\":42}",
        "{\"schemaVersion\":1,\"orderId\":42,\"requestedAt\":\"2026-10-29T01:10:00Z\"}",
        "{\"commandType\":\"RepublishOrderConfirmed\",\"schemaVersion\":1,\"orderId\":99,\"requestedAt\":\"2026-10-29T01:10:00Z\"}"
    })
    @DisplayName("깨진 JSON·필수 필드 누락·형식 오류·key와 orderId 불일치는 고칠 수 없는 명령이다")
    void brokenCommandIsInvalid(String json) {
        assertThat(parse("42", json)).isInstanceOf(RepublishRequestParseResult.Invalid.class);
    }

    private RepublishRequestParseResult parse(String key, String json) {
        return parser.parse(key, json.getBytes(StandardCharsets.UTF_8));
    }
}
