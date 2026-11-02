package com.loopers.support;

/** 테스트용 OrderConfirmed v1 JSON. 실제 발행 형식(commerce-api OrderConfirmedMessage)과 같은 모양. */
public final class OrderConfirmedJson {
    public static final String CONFIRMED_AT = "2026-09-07T01:00:00.123456Z";

    private OrderConfirmedJson() {
    }

    /** items: productId, quantity 쌍을 이어서. 예) of(42, 7, 2, 8, 1). */
    public static String of(long orderId, long... productIdAndQuantity) {
        return of("order-confirmed:v1:" + orderId, orderId, CONFIRMED_AT, productIdAndQuantity);
    }

    public static String of(String eventId, long orderId, String confirmedAt, long... productIdAndQuantity) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < productIdAndQuantity.length; i += 2) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"productId\":").append(productIdAndQuantity[i]).append(",\"quantity\":").append(productIdAndQuantity[i + 1]).append('}');
        }
        return "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
            + ",\"confirmedAt\":\"" + confirmedAt + "\",\"items\":[" + items + "]}";
    }
}
