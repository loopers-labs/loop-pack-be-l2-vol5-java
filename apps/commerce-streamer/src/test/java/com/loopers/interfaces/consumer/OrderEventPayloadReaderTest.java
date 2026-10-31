package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.domain.purchase.ConfirmedPurchase;
import com.loopers.domain.purchase.PurchaseReading;
import com.loopers.domain.purchase.UnreadableReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {JacksonAutoConfiguration.class, OrderEventPayloadReaderTest.AppJackson.class})
class OrderEventPayloadReaderTest {

    @Configuration
    @ComponentScan("com.loopers.config.jackson")
    static class AppJackson {
    }

    private final ObjectMapper objectMapper;
    private final OrderEventPayloadReader reader;

    @Autowired
    OrderEventPayloadReaderTest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.reader = new OrderEventPayloadReader(objectMapper);
    }

    private Optional<PurchaseReading> read(byte[] value) {
        return reader.read("42", value);
    }

    private byte[] example() {
        return OrderConfirmedContract.example().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] variant(Consumer<ObjectNode> change) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(example());
        change.accept(node);
        return objectMapper.writeValueAsBytes(node);
    }

    private PurchaseReading confirmed(Optional<PurchaseReading> reading) {
        assertThat(reading).isPresent();
        return reading.get();
    }

    private UnreadableReason unreadable(Optional<PurchaseReading> reading) {
        PurchaseReading purchase = confirmed(reading);
        assertThat(purchase).isInstanceOf(PurchaseReading.Unreadable.class);
        return ((PurchaseReading.Unreadable) purchase).reason();
    }

    @DisplayName("ACT-013: 공유 계약 예시를 읽으면 주문 42 의 확정 사실이 되고, 품목은 7:2,8:1, 확정 시각은 계약의 값이다")
    @Test
    void readsContractExample() {
        PurchaseReading reading = confirmed(read(example()));

        assertThat(reading).isInstanceOf(PurchaseReading.Readable.class);
        ConfirmedPurchase purchase = ((PurchaseReading.Readable) reading).purchase();
        assertThat(purchase.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(purchase.orderId()).isEqualTo(42L);
        assertThat(purchase.confirmedAt()).isEqualTo(Instant.parse("2026-10-30T01:02:03.120000Z"));
        assertThat(purchase.itemsDigest()).isEqualTo("7:2,8:1");
    }

    @DisplayName("ACT-009: JSON 이 깨졌으면 읽을 수 없음이다")
    @Test
    void brokenJsonIsMalformed() {
        assertThat(unreadable(read("{\"eventType\":".getBytes(StandardCharsets.UTF_8))))
            .isEqualTo(UnreadableReason.MALFORMED);
    }

    @DisplayName("ACT-009: 필드 타입이 맞지 않으면 읽을 수 없음이다")
    @Test
    void wrongTypesAreMalformed() throws Exception {
        assertThat(unreadable(read(variant(node -> node.put("orderId", "마흔둘")))))
            .isEqualTo(UnreadableReason.MALFORMED);
        assertThat(unreadable(read(variant(node -> node.put("items", "7:2")))))
            .isEqualTo(UnreadableReason.MALFORMED);
        assertThat(unreadable(read(variant(node -> node.put("confirmedAt", "어제")))))
            .isEqualTo(UnreadableReason.MALFORMED);
    }

    @DisplayName("ACT-009: eventType 이나 schemaVersion 이 없으면 고쳐 배포해도 읽을 수 없으므로 읽을 수 없음이다")
    @Test
    void missingEnvelopeIsMalformed() throws Exception {
        assertThat(unreadable(read(variant(node -> node.remove("eventType")))))
            .isEqualTo(UnreadableReason.MALFORMED);
        assertThat(unreadable(read(variant(node -> node.remove("schemaVersion")))))
            .isEqualTo(UnreadableReason.MALFORMED);
    }

    @DisplayName("ACT-009: 내용의 판정은 도메인이 한다. eventId 가 없으면 BLANK_EVENT_ID 다")
    @Test
    void delegatesContentToDomain() throws Exception {
        assertThat(unreadable(read(variant(node -> node.remove("eventId")))))
            .isEqualTo(UnreadableReason.BLANK_EVENT_ID);
    }

    @DisplayName("ACT-010: 모르는 schemaVersion 은 보류하지 않고 예외로 멈춘다. 소비자를 고쳐 배포하면 그 record 부터 다시 읽는다")
    @Test
    void unknownSchemaVersionStops() throws Exception {
        byte[] nextVersion = variant(node -> node.put("schemaVersion", 2));

        assertThatThrownBy(() -> read(nextVersion))
            .isInstanceOf(UnsupportedSchemaVersionException.class)
            .hasMessageContaining("2");
    }

    @DisplayName("ACT-016: payload 의 eventType 이 OrderConfirmed 가 아니면 다루지 않는다. 그 이벤트의 버전은 보지 않는다")
    @Test
    void otherEventTypeIsNotHandled() throws Exception {
        Optional<PurchaseReading> reading = read(variant(node -> {
            node.put("eventType", "OrderCancelled");
            node.put("schemaVersion", 9);
        }));

        assertThat(reading).isEmpty();
    }
}
