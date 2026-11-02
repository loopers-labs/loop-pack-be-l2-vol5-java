package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.OrderConfirmationViolation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * streamer는 "이 JSON을 읽는다"를 검증한다(docs/week6/decisions.md D-24). api의 계약 테스트와 같은 파일
 * (docs/week6/contract/order-confirmed.v1.json)을 build.gradle.kts의 contract.dir로 읽는다 — 어긋나면 둘 중 하나가 깨진다.
 */
class OrderConfirmedMessageReaderTest {

    private final OrderConfirmedMessageReader reader = new OrderConfirmedMessageReader();

    private static byte[] contract() throws IOException {
        String dir = System.getProperty("contract.dir");
        assertThat(dir).as("contract.dir 시스템 프로퍼티(build.gradle.kts tasks.test)").isNotBlank();
        return Files.readAllBytes(Path.of(dir, "order-confirmed.v1.json"));
    }

    private String violation(String key, String json) {
        return assertThrows(OrderConfirmationViolation.class,
            () -> reader.read(key, json.getBytes(StandardCharsets.UTF_8))).reason();
    }

    @DisplayName("계약 예시 JSON(주문 42)을 key \"42\"로 읽으면 기대한 확정 사실이 된다.")
    @Test
    void readsContractExample() throws IOException {
        // act
        OrderConfirmation confirmation = reader.read("42", contract());

        // assert
        assertThat(confirmation).isEqualTo(new OrderConfirmation("order-confirmed:v1:42", 42L,
            ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 123_456_000, ZoneOffset.UTC),
            List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1))));
    }

    @DisplayName("JSON이 아니거나 UTF-8로 읽을 수 없는 바이트면 계약 깨짐이다.")
    @Test
    void rejectsUnreadableBytes() {
        assertThat(violation("42", "{\"eventType\":")).contains("JSON");
        assertThat(violation("42", "{} trailing")).contains("JSON");
        assertThat(assertThrows(OrderConfirmationViolation.class,
            () -> reader.read("42", new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00})).reason()).contains("JSON");
        assertThat(assertThrows(OrderConfirmationViolation.class, () -> reader.read("42", null)).reason()).contains("null");
    }

    @DisplayName("JSON 객체가 아니면 계약 깨짐이다.")
    @Test
    void rejectsNonObject() {
        assertThat(violation("42", "[1,2]")).contains("객체");
    }

    @DisplayName("칸의 타입이 다르면(숫자 자리에 문자열, 정수 자리에 소수, items가 배열이 아님) 계약 깨짐이다.")
    @Test
    void rejectsWrongTypes() throws IOException {
        String contract = new String(contract(), StandardCharsets.UTF_8);
        assertThat(violation("42", contract.replace("\"orderId\": 42", "\"orderId\": \"42\""))).contains("orderId");
        assertThat(violation("42", contract.replace("\"quantity\": 2", "\"quantity\": 2.5"))).contains("quantity");
        assertThat(violation("42", contract.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""))).contains("schemaVersion");
        assertThat(violation("42", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:42\","
            + "\"orderId\":42,\"confirmedAt\":\"2026-09-07T01:00:00.123456Z\",\"items\":{}}")).contains("items");
    }
}
