package com.loopers.domain.purchase.vo;

import com.loopers.support.error.RejectReason;
import com.loopers.support.error.RejectedEventException;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 받은 주문 확정 사실. 어느 입력에서 왔든 지켜야 하는 규칙을 생성할 때 검사함 (6주차 설계 4.2).
 * 합산 · 절사로 받아 주면 producer 의 계약 위반을 숨기므로 고치지 않고 거절함 (D-80, D-84)
 */
public record OrderConfirmation(String eventId, Long orderId, ZonedDateTime confirmedAt, List<Item> items) {

    public record Item(Long productId, int quantity) {}

    public OrderConfirmation {
        if (items == null || items.isEmpty()) {
            throw new RejectedEventException(RejectReason.EMPTY_ITEMS, "eventId=" + eventId);
        }
        Set<Long> productIds = new HashSet<>();
        for (Item item : items) {
            if (item.quantity() < 1) {
                throw new RejectedEventException(RejectReason.INVALID_QUANTITY, "eventId=" + eventId + " productId=" + item.productId());
            }
            if (!productIds.add(item.productId())) {
                throw new RejectedEventException(RejectReason.DUPLICATE_PRODUCT, "eventId=" + eventId + " productId=" + item.productId());
            }
        }
        if (confirmedAt.getNano() % 1_000 != 0) {
            throw new RejectedEventException(RejectReason.TOO_PRECISE_TIME, "eventId=" + eventId + " confirmedAt=" + confirmedAt);
        }
        items = List.copyOf(items);
    }

    public Map<Long, Integer> quantityByProduct() {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        items.forEach(item -> quantities.put(item.productId(), item.quantity()));
        return quantities;
    }
}
