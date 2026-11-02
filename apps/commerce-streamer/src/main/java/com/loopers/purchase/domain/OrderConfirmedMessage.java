package com.loopers.purchase.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 받은 OrderConfirmed v1 (W6 D-2·D-6). 계약에 맞지 않으면 만들어지지 않는다 — 재시도해도 같으므로 DLT 대상이다.
 * <p>
 * 같은 사실인지는 fingerprint로 본다. 사실 = 주문 ID · 확정 순간 · 상품별 수량. JSON 속성·items 순서, offset 표기(+09:00 / Z),
 * 같은 상품의 분할은 사실이 아니다. eventId도 사실이 아니다 — 같은 주문·다른 eventId는 판정에서 따로 잡는다.
 */
public record OrderConfirmedMessage(
    String eventId,
    String eventType,
    int schemaVersion,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {

    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    public record Item(Long productId, int quantity) {}

    public OrderConfirmedMessage {
        require(eventId != null && !eventId.isBlank(), "eventId가 없습니다");
        require(EVENT_TYPE.equals(eventType), "eventType이 OrderConfirmed가 아닙니다: " + eventType);
        require(schemaVersion == SCHEMA_VERSION, "지원하지 않는 schemaVersion: " + schemaVersion);
        require(orderId != null, "orderId가 없습니다");
        require(confirmedAt != null, "confirmedAt이 없습니다");
        require(items != null && !items.isEmpty(), "items가 비어 있습니다");
        for (Item item : items) {
            require(item.productId() != null, "productId가 없는 품목이 있습니다");
            require(item.quantity() > 0, "수량은 양수여야 합니다: productId=" + item.productId() + ", quantity=" + item.quantity());
        }
        try {
            OffsetDateTime.parse(confirmedAt);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("confirmedAt 형식이 아닙니다: " + confirmedAt, e);
        }
        items = List.copyOf(items);
    }

    /**
     * 저장할 품목 — 같은 상품을 합쳐 상품 ID 오름차순으로.
     */
    public List<Item> normalizedItems() {
        Map<Long, Integer> merged = new TreeMap<>();
        items.forEach(item -> merged.merge(item.productId(), item.quantity(), Integer::sum));
        return merged.entrySet().stream().map(entry -> new Item(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * 확정 순간을 UTC·마이크로초로 고정한 문자열 — 표기가 달라도 같은 순간이면 같다.
     */
    public String confirmedAtUtc() {
        return OffsetDateTime.parse(confirmedAt).withOffsetSameInstant(ZoneOffset.UTC).format(UTC_MICROS);
    }

    /**
     * 정규화한 사실의 SHA-256. 입력 예: "42|2026-10-28T05:03:12.123456Z|7:2,8:1"
     */
    public String fingerprint() {
        String facts = normalizedItems().stream()
            .map(item -> item.productId() + ":" + item.quantity())
            .collect(Collectors.joining(","));
        return sha256(orderId + "|" + confirmedAtUtc() + "|" + facts);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
