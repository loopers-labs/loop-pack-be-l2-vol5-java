package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * api는 "이 JSON을 만든다"를 검증한다(docs/week6/decisions.md D-24). 계약 파일은 streamer도 같은 것을 읽는다 —
 * 어긋나면 둘 중 하나가 깨진다. 경로는 build.gradle.kts의 contract.dir 시스템 프로퍼티로 받는다.
 *
 * <p>7주차부터 JSON은 보낼 때가 아니라 확정 TX에서 Outbox payload로 한 번 만든다(docs/week7/decisions.md D-1, D-12).
 * 그래서 이 검증은 Kafka 전송 테스트에서 직렬화 구현({@link OrderConfirmedJson}) 자체로 옮겨 왔다. 확인 지점 ①
 * "Outbox payload == 계약 JSON"의 단위 쪽이다. ② "Kafka value == Outbox payload"는 KafkaOrderConfirmedPublisherContractTest가 본다.
 */
class OrderConfirmedJsonContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OrderConfirmedJson serializer = new OrderConfirmedJson();

    private static JsonNode contract() throws IOException {
        String dir = System.getProperty("contract.dir");
        assertThat(dir).as("contract.dir 시스템 프로퍼티(build.gradle.kts tasks.test)").isNotBlank();
        return MAPPER.readTree(Files.readString(Path.of(dir, "order-confirmed.v1.json")));
    }

    private static OrderConfirmedPayload payload42(ZonedDateTime confirmedAt) {
        return new OrderConfirmedPayload(OrderConfirmedPayload.EVENT_TYPE, OrderConfirmedPayload.SCHEMA_VERSION,
            OrderConfirmedPayload.eventIdOf(42L), 42L, confirmedAt,
            List.of(new OrderConfirmedPayload.Item(7L, 2), new OrderConfirmedPayload.Item(8L, 1)));
    }

    private String confirmedAtOf(ZonedDateTime confirmedAt) throws IOException {
        return MAPPER.readTree(serializer.write(payload42(confirmedAt))).get("confirmedAt").asText();
    }

    @DisplayName("계약 예시(주문 42)를 쓰면 계약 JSON과 같고, confirmedAt은 글자 그대로 같다.")
    @Test
    void producesContractJson() throws IOException {
        // arrange
        JsonNode contract = contract();
        ZonedDateTime confirmedAt = ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 123_456_000, ZoneOffset.UTC);

        // act
        JsonNode actual = MAPPER.readTree(serializer.write(payload42(confirmedAt)));

        // assert
        assertThat(actual).isEqualTo(contract);
        assertThat(actual.get("confirmedAt").asText()).isEqualTo(contract.get("confirmedAt").asText());
    }

    @DisplayName("confirmedAt 끝자리가 0이어도 소수 6자리를 그대로 쓴다. (.120000)")
    @Test
    void keepsTrailingZeros() throws IOException {
        assertThat(confirmedAtOf(ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 120_000_000, ZoneOffset.UTC)))
            .isEqualTo("2026-09-07T01:00:00.120000Z");
    }

    @DisplayName("confirmedAt 소수가 0이어도 .000000을 쓴다.")
    @Test
    void keepsZeroFraction() throws IOException {
        assertThat(confirmedAtOf(ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 0, ZoneOffset.UTC)))
            .isEqualTo("2026-09-07T01:00:00.000000Z");
    }

    @DisplayName("다른 시간대로 들어온 같은 순간도 UTC(Z)로 쓴다.")
    @Test
    void writesInUtc() throws IOException {
        assertThat(confirmedAtOf(ZonedDateTime.of(2026, 9, 7, 10, 0, 0, 123_456_000, ZoneId.of("Asia/Seoul"))))
            .isEqualTo("2026-09-07T01:00:00.123456Z");
    }
}
