package com.loopers.domain.purchase;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 확정 메시지에서 가져온 주문별 구매 사실. 현재 상품 정보를 조회하지 않는다. */
public record OrderPurchase(String eventId, Long orderId, Instant confirmedAt, List<Item> items) {
    public OrderPurchase {
        if (eventId == null || eventId.isBlank() || eventId.length() > 255
            || orderId == null || orderId <= 0 || confirmedAt == null || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("주문의 구매 사실이 올바르지 않습니다.");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Item item : items) {
            if (item == null) {
                throw new IllegalArgumentException("구매 품목이 필요합니다.");
            }
            quantities.merge(item.productId(), item.quantity(), Math::addExact);
        }
        items = quantities.entrySet().stream().map(entry -> new Item(entry.getKey(), entry.getValue())).toList();
    }

    /** ID·시각·정규화된 상품별 수량의 비교용 표현. Kafka JSON의 필드·배열 순서와 무관하다. */
    public String factSnapshot() {
        String quantities = items.stream().map(item -> item.productId() + ":" + item.quantity())
            .collect(Collectors.joining(","));
        return orderId + "|" + confirmedAt + "|" + quantities;
    }

    public record Item(Long productId, int quantity) {
        public Item {
            if (productId == null || productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("상품 ID와 수량은 양수여야 합니다.");
            }
        }
    }
}
