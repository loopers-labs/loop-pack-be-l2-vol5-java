package com.loopers.application.purchase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

public record OrderConfirmedEvent(String eventId, long orderId, Instant confirmedAt, List<PurchasedItem> items) {

    public OrderConfirmedEvent {
        items = List.copyOf(items);
    }

    // 같은 사실인지 비교하는 기준이다. JSON 속성 순서·items 순서·모르는 필드·시각의 표기 offset은 사실이 아니므로 빼고,
    // 계약 필드만 정해진 순서로 이어 SHA-256으로 줄인다.
    public String fingerprint() {
        String canonicalItems = items.stream()
            .sorted(Comparator.comparingLong(PurchasedItem::productId))
            .map(item -> item.productId() + ":" + item.quantity())
            .collect(Collectors.joining(","));
        String canonical = "eventId=" + eventId
            + "\norderId=" + orderId
            + "\nconfirmedAt=" + confirmedAt
            + "\nitems=" + canonicalItems;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없음", e);
        }
    }
}
