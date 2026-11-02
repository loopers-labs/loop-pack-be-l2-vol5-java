package com.loopers.domain.purchase;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * "같은 사실"을 글자로 비교하기 위한 정규 형식(docs/week6/decisions.md D-7, D-27).
 * 예: {@code orderId=42|confirmedAt=2026-09-07T01:00:00.123456Z|items=7:2,8:1}
 *
 * - 범위는 orderId, confirmedAt, items뿐이다. eventType·schemaVersion·eventId·처리 시각·partition·offset은 넣지 않는다 —
 *   재전달마다 달라지는 값을 넣으면 정상 재전달이 전부 conflict가 된다.
 * - 받은 JSON을 그대로 남기지 않고 읽은 값으로 다시 만든다. producer의 필드 순서·공백과 상관없이 같은 사실은 같은 문자열이다.
 * - items는 productId 오름차순, 시각은 UTC 소수 6자리 고정(D-6, D-25와 같은 모양).
 */
public record PurchaseFact(String value) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    public static PurchaseFact of(OrderConfirmation confirmation) {
        return of(confirmation.orderId(), confirmation.confirmedAt(), confirmation.items());
    }

    public static PurchaseFact of(Long orderId, ZonedDateTime confirmedAt, List<OrderConfirmation.Item> items) {
        String itemText = items.stream()
            .sorted(Comparator.comparing(OrderConfirmation.Item::productId))
            .map(item -> item.productId() + ":" + item.quantity())
            .collect(Collectors.joining(","));
        return new PurchaseFact("orderId=" + orderId
            + "|confirmedAt=" + confirmedAt.withZoneSameInstant(ZoneOffset.UTC).format(TIME)
            + "|items=" + itemText);
    }
}
