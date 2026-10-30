package com.loopers.domain.purchase;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 확정된 주문 하나의 구매 사실. 같은 상품은 합산하고 상품 ID 순으로 정렬하며, 확정 시각은 DB 가 저장하는 마이크로초로
 * 맞춘다. 저장된 사실과 비교할 때 표현 차이가 충돌로 보이지 않게 하려는 것이다.
 */
public record OrderConfirmation(String eventId, Long orderId, ZonedDateTime confirmedAt, List<PurchaseLine> lines) {

    public OrderConfirmation {
        if (eventId == null || eventId.isBlank() || orderId == null || confirmedAt == null) {
            throw new IllegalArgumentException("eventId, orderId, confirmedAt 은 필수입니다.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("구매 품목은 하나 이상이어야 합니다.");
        }
        confirmedAt = confirmedAt.truncatedTo(ChronoUnit.MICROS);
        lines = normalize(lines);
    }

    private static List<PurchaseLine> normalize(List<PurchaseLine> lines) {
        Map<Long, Integer> quantities = new TreeMap<>();
        lines.forEach(line -> quantities.merge(line.productId(), line.quantity(), Integer::sum));
        return quantities.entrySet().stream().map(entry -> new PurchaseLine(entry.getKey(), entry.getValue())).toList();
    }
}
