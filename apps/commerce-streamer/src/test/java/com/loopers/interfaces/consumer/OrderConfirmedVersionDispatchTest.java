package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.OrderConfirmation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderConfirmedVersionDispatchTest {
    @DisplayName("추가 버전의 해석기를 등록해도 기존 버전과 각각의 해석 규칙을 함께 지원한다.")
    @Test
    void selectsRegisteredReaderAndKeepsExistingVersion() {
        OrderConfirmation first = confirmation(2);
        OrderConfirmation second = confirmation(3);
        OrderConfirmedVersionReader firstReader = reader(1, first);
        OrderConfirmedVersionReader secondReader = reader(2, second);
        var parser = new OrderConfirmedParser(new ObjectMapper(), List.of(firstReader, secondReader));

        assertThat(parser.parseAndValidate("42", payload(1))).isEqualTo(first);
        assertThat(parser.parseAndValidate("42", payload(2))).isEqualTo(second);
        verify(firstReader).read(any(JsonNode.class));
        verify(secondReader).read(any(JsonNode.class));
    }

    @DisplayName("지원 목록에 없는 버전은 다른 버전으로 추측해서 해석하지 않는다.")
    @Test
    void rejectsUnregisteredVersionWithoutCallingReader() {
        OrderConfirmedVersionReader registered = reader(1, confirmation(2));
        var parser = new OrderConfirmedParser(new ObjectMapper(), List.of(registered));

        assertThatThrownBy(() -> parser.parseAndValidate("42", payload(3)))
            .isInstanceOf(IllegalArgumentException.class);
        verify(registered, never()).read(any(JsonNode.class));
    }

    @DisplayName("같은 버전의 해석기를 둘 등록하면 모호한 구성을 시작 단계에서 거절한다.")
    @Test
    void rejectsDuplicateVersionRegistration() {
        OrderConfirmedVersionReader first = reader(1, confirmation(2));
        OrderConfirmedVersionReader duplicate = reader(1, confirmation(3));

        assertThatThrownBy(() -> new OrderConfirmedParser(new ObjectMapper(), List.of(first, duplicate)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("중복");
    }

    private static OrderConfirmedVersionReader reader(int version, OrderConfirmation result) {
        OrderConfirmedVersionReader reader = mock(OrderConfirmedVersionReader.class);
        when(reader.schemaVersion()).thenReturn(version);
        when(reader.read(any(JsonNode.class))).thenReturn(result);
        return reader;
    }

    private static OrderConfirmation confirmation(int quantity) {
        return new OrderConfirmation("order-confirmed:v1:42", 42L, Instant.parse("2026-09-07T01:00:00Z"),
            List.of(new OrderConfirmation.Item(7L, quantity)));
    }

    private static byte[] payload(int version) {
        return ("""
            {"eventType":"OrderConfirmed","schemaVersion":%d,"eventId":"order-confirmed:v1:42",
             "orderId":42,"confirmedAt":"2026-09-07T01:00:00Z","items":[{"productId":7,"quantity":2}]}
            """.formatted(version)).getBytes(StandardCharsets.UTF_8);
    }
}
