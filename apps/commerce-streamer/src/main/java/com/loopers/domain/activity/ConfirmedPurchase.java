package com.loopers.domain.activity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConfirmedPurchase {

    private final String eventId;
    private final Long orderId;
    private final Instant confirmedAt;
    private final List<PurchaseLine> items;

    private ConfirmedPurchase(String eventId, Long orderId, Instant confirmedAt, List<PurchaseLine> items) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.confirmedAt = confirmedAt.truncatedTo(ChronoUnit.MICROS);
        this.items = items.stream()
            .sorted(Comparator.comparing(PurchaseLine::productId))
            .toList();
    }

    public static PurchaseReading read(String key, String eventId, Long orderId, Instant confirmedAt,
                                       List<PurchaseLine> items) {
        UnreadableReason reason = unreadableReason(key, eventId, orderId, confirmedAt, items);
        if (reason != null) {
            return new PurchaseReading.Unreadable(reason);
        }
        return new PurchaseReading.Readable(new ConfirmedPurchase(eventId, orderId, confirmedAt, items));
    }

    private static UnreadableReason unreadableReason(String key, String eventId, Long orderId,
                                                     Instant confirmedAt, List<PurchaseLine> items) {
        if (eventId == null || eventId.isBlank()) {
            return UnreadableReason.BLANK_EVENT_ID;
        }
        if (orderId == null) {
            return UnreadableReason.MISSING_ORDER_ID;
        }
        if (!String.valueOf(orderId).equals(key)) {
            return UnreadableReason.KEY_MISMATCH;
        }
        if (confirmedAt == null) {
            return UnreadableReason.MISSING_CONFIRMED_AT;
        }
        if (items == null || items.isEmpty()) {
            return UnreadableReason.EMPTY_ITEMS;
        }
        Set<Long> seen = new HashSet<>();
        for (PurchaseLine item : items) {
            if (item.productId() == null) {
                return UnreadableReason.MISSING_PRODUCT_ID;
            }
            if (item.quantity() <= 0) {
                return UnreadableReason.NON_POSITIVE_QUANTITY;
            }
            if (!seen.add(item.productId())) {
                return UnreadableReason.DUPLICATE_PRODUCT;
            }
        }
        return null;
    }

    public boolean isSameFactAs(Long storedOrderId, Instant storedConfirmedAt, String storedItemsDigest) {
        return Objects.equals(orderId, storedOrderId)
            && Objects.equals(confirmedAt, storedConfirmedAt)
            && Objects.equals(itemsDigest(), storedItemsDigest);
    }

    public ReceiptCollision collideWith(Optional<RecordedConfirmation> sameEvent,
                                        Optional<RecordedConfirmation> sameOrder) {
        if (sameEvent.isPresent()) {
            RecordedConfirmation recorded = sameEvent.get();
            return isSameFactAs(recorded.orderId(), recorded.confirmedAt(), recorded.itemsDigest())
                ? ReceiptCollision.SAME_FACT
                : ReceiptCollision.EVENT_CONFLICT;
        }
        if (sameOrder.isPresent()) {
            return ReceiptCollision.ORDER_CONFLICT;
        }
        return ReceiptCollision.NOT_RECORDED;
    }

    public String itemsDigest() {
        return items.stream()
            .map(item -> item.productId() + ":" + item.quantity())
            .collect(Collectors.joining(","));
    }

    public String eventId() {
        return eventId;
    }

    public Long orderId() {
        return orderId;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public List<PurchaseLine> items() {
        return items;
    }
}
