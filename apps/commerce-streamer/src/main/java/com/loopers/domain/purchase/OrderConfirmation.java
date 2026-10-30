package com.loopers.domain.purchase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

public record OrderConfirmation(String eventId, long orderId, Instant confirmedAt, List<Item> items) {
    public OrderConfirmation {
        if (eventId == null || eventId.isBlank() || eventId.length() > 255 || orderId <= 0 || confirmedAt == null
            || items == null || items.isEmpty() || items.stream().anyMatch(item -> item == null)) {
            throw new IllegalArgumentException("확정 이벤트의 식별자·확정 시각·품목이 유효하지 않습니다.");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        try {
            items.forEach(item -> quantities.merge(item.productId(), item.quantity(), Math::addExact));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("상품별 구매 수량이 허용 범위를 넘었습니다.", e);
        }
        items = quantities.entrySet().stream().map(entry -> new Item(entry.getKey(), entry.getValue())).toList();
        confirmedAt = confirmedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public List<ProductPurchaseActivity> activities() {
        return items.stream()
            .map(item -> ProductPurchaseActivity.create(orderId, item.productId(), item.quantity(), confirmedAt))
            .toList();
    }

    public void verifyExisting(List<ProcessedOrderConfirmation> processed, List<ProductPurchaseActivity> savedActivities) {
        if (processed.size() != 1 || !processed.getFirst().eventId().equals(eventId)
            || processed.getFirst().orderId() != orderId) {
            throw new OrderConfirmationConflictException(
                "확정 이벤트 식별자 충돌: receivedEventId=" + eventId + ", receivedOrderId=" + orderId + ", stored=" + processed);
        }
        List<ProductPurchaseActivity> sorted = savedActivities.stream()
            .sorted(Comparator.comparingLong(ProductPurchaseActivity::productId)).toList();
        if (!activities().equals(sorted)) {
            throw new OrderConfirmationConflictException(
                "구매 사실 충돌: eventId=" + eventId + ", orderId=" + orderId
                    + ", stored=" + sorted + ", received=" + activities());
        }
    }

    public record Item(long productId, int quantity) {
        public Item {
            if (productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("상품 식별자와 구매 수량은 양수여야 합니다.");
            }
        }
    }
}
