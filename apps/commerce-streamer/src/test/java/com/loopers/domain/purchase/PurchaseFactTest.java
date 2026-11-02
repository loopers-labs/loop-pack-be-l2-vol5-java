package com.loopers.domain.purchase;

import com.loopers.application.purchase.OrderConfirmedMessageReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 처음 사실의 정규 형식(docs/week6/decisions.md D-7, D-27).
 */
class PurchaseFactTest {

    private final OrderConfirmedMessageReader reader = new OrderConfirmedMessageReader();

    private PurchaseFact factOf(String json) {
        return PurchaseFact.of(reader.read("42", json.getBytes(StandardCharsets.UTF_8)));
    }

    @DisplayName("계약 예시는 orderId=42|confirmedAt=2026-09-07T01:00:00.123456Z|items=7:2,8:1 이 된다.")
    @Test
    void canonicalForm() {
        // act
        PurchaseFact fact = PurchaseFact.of(42L, ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 123_456_000, ZoneOffset.UTC),
            List.of(new OrderConfirmation.Item(8L, 1), new OrderConfirmation.Item(7L, 2)));

        // assert
        assertThat(fact.value()).isEqualTo("orderId=42|confirmedAt=2026-09-07T01:00:00.123456Z|items=7:2,8:1");
    }

    @DisplayName("JSON의 필드 순서·items 순서·공백이 달라도 같은 사실이면 같은 문자열이다.")
    @Test
    void ignoresFieldOrderItemOrderAndWhitespace() {
        // arrange
        String compact = "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:42\","
            + "\"orderId\":42,\"confirmedAt\":\"2026-09-07T01:00:00.123456Z\","
            + "\"items\":[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]}";
        String shuffled = "{\n  \"items\" : [ { \"quantity\" : 1, \"productId\" : 8 },\n { \"productId\" : 7, \"quantity\" : 2 } ],\n"
            + "  \"confirmedAt\" : \"2026-09-07T01:00:00.123456Z\", \"orderId\" : 42,\n"
            + "  \"eventId\" : \"order-confirmed:v1:42\", \"schemaVersion\" : 1, \"eventType\" : \"OrderConfirmed\"\n}";

        // act & assert
        assertThat(factOf(shuffled)).isEqualTo(factOf(compact));
    }

    @DisplayName("같은 순간이면 시간대가 달라도 UTC 소수 6자리로 같은 문자열이다. 끝자리 0도 지우지 않는다.")
    @Test
    void sameInstantSameString() {
        // arrange
        List<OrderConfirmation.Item> items = List.of(new OrderConfirmation.Item(7L, 2));
        ZonedDateTime utc = ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 120_000_000, ZoneOffset.UTC);

        // act
        PurchaseFact a = PurchaseFact.of(42L, utc, items);
        PurchaseFact b = PurchaseFact.of(42L, utc.withZoneSameInstant(ZoneId.of("Asia/Seoul")), items);

        // assert
        assertThat(a).isEqualTo(b);
        assertThat(a.value()).contains("confirmedAt=2026-09-07T01:00:00.120000Z");
    }

    @DisplayName("수량이 다르면 다른 문자열이다.")
    @Test
    void differentQuantityDifferentString() {
        // arrange
        ZonedDateTime at = ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 0, ZoneOffset.UTC);

        // act
        PurchaseFact two = PurchaseFact.of(42L, at, List.of(new OrderConfirmation.Item(7L, 2)));
        PurchaseFact three = PurchaseFact.of(42L, at, List.of(new OrderConfirmation.Item(7L, 3)));

        // assert
        assertThat(two).isNotEqualTo(three);
    }
}
