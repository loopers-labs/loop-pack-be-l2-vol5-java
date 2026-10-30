package com.loopers.order.domain;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 주문 확정 이벤트 (W6 D-2). 확정 주문의 저장된 사실(품목·수량·최초 확정 시각)에서만 만든다 — 소비 시점의 상품 조회로 덮지 않는다.
 * 카드·가격·상품명·사용자는 넣지 않는다 (C-3). JSON은 이 순서대로 직렬화된다.
 *
 * @param eventId       최초 확정 때 한 번 만든 UUID (ADR-W6-01)
 * @param confirmedAt   최초 확정 시각, KST(+09:00) · 마이크로초 6자리 고정 형식 (예: 2026-10-28T14:03:12.123456+09:00)
 * @param items         상품 ID 오름차순. 같은 상품은 주문 생성 때 이미 합쳐져 있다 (OrderLines)
 */
public record OrderConfirmed(
    String eventId,
    String eventType,
    int schemaVersion,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {

    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    /** 소수부가 0이어도 6자리를 쓴다 — 같은 시각이 값에 따라 다른 문자열이 되지 않게 */
    private static final DateTimeFormatter CONFIRMED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");

    /** 확정 시각을 어느 zone으로 읽었든(확정 TX 안 / DB에서 다시 읽은 UTC) 같은 표기가 되게 */
    private static final ZoneId CONFIRMED_AT_ZONE = ZoneId.of("Asia/Seoul");

    public record Item(Long productId, int quantity) {}

    public static OrderConfirmed from(String eventId, OrderModel order) {
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new IllegalStateException("확정된 주문만 OrderConfirmed를 만든다. orderId=" + order.getId() + ", status=" + order.getStatus());
        }
        List<Item> items = order.getItems().stream()
            .map(item -> new Item(item.getProductId(), item.getQuantity()))
            .sorted(Comparator.comparing(Item::productId))
            .toList();
        return new OrderConfirmed(
            eventId,
            EVENT_TYPE,
            SCHEMA_VERSION,
            order.getId(),
            order.getConfirmedAt().withZoneSameInstant(CONFIRMED_AT_ZONE).format(CONFIRMED_AT_FORMAT),
            items
        );
    }
}
