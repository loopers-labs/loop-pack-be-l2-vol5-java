package com.loopers.domain.activity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 주문 확정 사실 (OrderConfirmed v1 의 업무 값). 어느 확정 주문에서 어떤 상품을 몇 개 샀는가.
 * 만들 때 계약을 검증하고 품목을 productId 오름차순으로 정규화한다. 위반은 OrderConfirmationContractException (재시도해도 같은 결과).
 *
 * @param eventId     "order-confirmed:v1:{orderId}". 주문당 확정은 한 번이라 orderId 와 1:1
 * @param confirmedAt 최초 확정 시각. 소비 시각이 아니다
 */
public record OrderConfirmation(String eventId, Long orderId, Instant confirmedAt, List<Line> items) {

    public record Line(Long productId, Integer quantity) {
    }

    public OrderConfirmation {
        if (orderId == null || orderId <= 0) {
            throw new OrderConfirmationContractException("orderId 가 올바르지 않습니다. [orderId = " + orderId + "]");
        }
        if (!eventIdOf(orderId).equals(eventId)) {
            throw new OrderConfirmationContractException("eventId 가 orderId 와 맞지 않습니다. [eventId = " + eventId + ", orderId = " + orderId + "]");
        }
        if (confirmedAt == null) {
            throw new OrderConfirmationContractException("확정 시각이 없습니다. [eventId = " + eventId + "]");
        }
        if (items == null || items.isEmpty()) {
            throw new OrderConfirmationContractException("품목이 없습니다. [eventId = " + eventId + "]");
        }
        Set<Long> productIds = new HashSet<>();
        for (Line line : items) {
            if (line == null || line.productId() == null || line.quantity() == null || line.quantity() <= 0) {
                throw new OrderConfirmationContractException("품목의 상품·수량이 올바르지 않습니다. [eventId = " + eventId + ", item = " + line + "]");
            }
            if (!productIds.add(line.productId())) {
                throw new OrderConfirmationContractException("같은 상품이 두 번 있습니다. [eventId = " + eventId + ", productId = " + line.productId() + "]");
            }
        }
        items = items.stream().sorted(Comparator.comparing(Line::productId)).toList();
    }

    public static String eventIdOf(Long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    /**
     * 고정 payload 의 지문 (SHA-256 hex). eventId·orderId·확정 시각·정규화한 품목으로 계산하므로 JSON 속성·배열 순서가 달라도 같다.
     * 같은 eventId 의 재전달이 같은 사실인지 비교할 때 쓴다.
     */
    public String fingerprint() {
        String canonical = eventId + "|" + orderId + "|" + confirmedAt + "|"
            + items.stream().map(line -> line.productId() + ":" + line.quantity()).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
