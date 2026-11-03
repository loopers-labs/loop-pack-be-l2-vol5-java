package com.loopers.domain.purchase;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 파싱·검증을 마친 주문 확정 사실 (설계 1절 계약). items 는 같은 상품을 합산하고 productId 오름차순으로 정규화한다.
 * fingerprint 는 정규화한 값으로 만들므로 JSON 속성 순서·items 순서가 바뀌어도 같은 값이 나온다 (📄 p.2).
 */
public record OrderConfirmedEvent(String eventId, Long orderId, ZonedDateTime confirmedAt, List<Item> items) {

    public record Item(Long productId, int quantity) {
    }

    public OrderConfirmedEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new PermanentEventException("eventId 가 없습니다.");
        }
        if (orderId == null) {
            throw new PermanentEventException("orderId 가 없습니다.");
        }
        if (confirmedAt == null) {
            throw new PermanentEventException("confirmedAt 이 없습니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new PermanentEventException("items 가 비어 있습니다. eventId=" + eventId);
        }
        items = normalize(items, eventId);
    }

    /**
     * 같은 productId 는 수량을 합산해 한 줄로, productId 오름차순으로. 수량은 양수여야 한다 (설계 1절).
     */
    private static List<Item> normalize(List<Item> rawItems, String eventId) {
        Map<Long, Integer> quantityByProduct = new TreeMap<>();
        for (Item item : rawItems) {
            if (item.productId() == null) {
                throw new PermanentEventException("productId 가 없는 품목이 있습니다. eventId=" + eventId);
            }
            if (item.quantity() <= 0) {
                throw new PermanentEventException(
                    "quantity 는 양수여야 합니다. eventId=" + eventId + ", productId=" + item.productId() + ", quantity=" + item.quantity());
            }
            quantityByProduct.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return quantityByProduct.entrySet().stream()
            .map(entry -> new Item(entry.getKey(), entry.getValue()))
            .toList();
    }

    /**
     * {@code orderId|confirmedAt(UTC)|productId:quantity,...} (설계 D10). 예: {@code 42|2026-09-07T01:00:00Z|7:2,8:1}
     */
    public String fingerprint() {
        String itemPart = items.stream()
            .map(item -> item.productId() + ":" + item.quantity())
            .collect(Collectors.joining(","));
        return orderId + "|" + confirmedAt.toInstant() + "|" + itemPart;
    }
}
