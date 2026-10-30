package com.loopers.domain.purchase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 소비한 확정 주문 한 건의 사실 (OrderConfirmed v1). 이벤트의 모양(JSON 속성 순서·items 배열 순서)이 아니라 정규화한 값으로 비교한다.
 *
 * <p>품목은 생성할 때 상품별로 합산하고 상품 id 순으로 정렬한다. 같은 사실은 항상 같은 {@link #fingerprint()}를 갖는다.
 * 소비 시점에 상품을 다시 조회하지 않는다. 과거 주문의 상품 id는 사실의 식별자로만 보존한다.
 */
public record OrderConfirmation(String eventId, long orderId, Instant confirmedAt, List<Item> items) {

    public record Item(long productId, int quantity) {
    }

    public OrderConfirmation {
        if (eventId == null || eventId.isBlank()) {
            throw new InvalidOrderEventException("eventId가 비어 있습니다.");
        }
        if (orderId <= 0) {
            throw new InvalidOrderEventException("orderId는 양수여야 합니다. orderId=" + orderId);
        }
        if (confirmedAt == null) {
            throw new InvalidOrderEventException("confirmedAt이 비어 있습니다.");
        }
        items = normalize(items);
    }

    /** 같은 확정 사실이면 같은 값. eventId는 포함하지 않는다(같은 사실이 다른 eventId로 오는 경우를 따로 구분하려고). */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder("v1|").append(orderId).append('|').append(confirmedAt);
        for (Item item : items) {
            canonical.append('|').append(item.productId()).append(':').append(item.quantity());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static List<Item> normalize(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidOrderEventException("items가 비어 있습니다.");
        }
        Map<Long, Integer> quantityByProduct = new TreeMap<>();
        for (Item item : items) {
            if (item.productId() <= 0) {
                throw new InvalidOrderEventException("productId는 양수여야 합니다. productId=" + item.productId());
            }
            if (item.quantity() <= 0) {
                throw new InvalidOrderEventException("quantity는 양수여야 합니다. productId=" + item.productId());
            }
            quantityByProduct.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return quantityByProduct.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toList();
    }
}
