package com.loopers.support;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class OrderConfirmedMessages {

    public static final String CONFIRMED_AT = "2026-10-29T10:00:00+09:00";

    private OrderConfirmedMessages() {
    }

    public static String json(String eventId, long orderId, long... productIdAndQuantity) {
        return json(1, eventId, orderId, productIdAndQuantity);
    }

    public static String json(int schemaVersion, String eventId, long orderId, long... productIdAndQuantity) {
        return """
            {"eventType":"OrderConfirmed","schemaVersion":%d,"eventId":"%s","orderId":%d,"confirmedAt":"%s","items":[%s]}"""
            .formatted(schemaVersion, eventId, orderId, CONFIRMED_AT, items(productIdAndQuantity));
    }

    public static String items(long... productIdAndQuantity) {
        if (productIdAndQuantity.length % 2 != 0) {
            throw new IllegalArgumentException("상품 ID와 수량을 쌍으로 넣어야 한다");
        }
        return Arrays.stream(pairs(productIdAndQuantity))
            .map(pair -> "{\"productId\":%d,\"quantity\":%d}".formatted(pair[0], pair[1]))
            .collect(Collectors.joining(","));
    }

    public static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static long[][] pairs(long[] values) {
        long[][] pairs = new long[values.length / 2][];
        for (int index = 0; index < pairs.length; index++) {
            pairs[index] = new long[] {values[index * 2], values[index * 2 + 1]};
        }
        return pairs;
    }
}
