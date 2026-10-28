package com.loopers.purchase.domain;

/**
 * 소비 완료 기록 (W6 D-6, Inbox 역할). Red 단계의 뼈대.
 */
public class ConsumedOrderConfirmation {

    private String eventId;
    private Long orderId;
    private String fingerprint;
    private String payload;

    protected ConsumedOrderConfirmation() {}

    public static ConsumedOrderConfirmation of(OrderConfirmedMessage message, String payload) {
        return new ConsumedOrderConfirmation();
    }

    public ConsumptionVerdict judge(OrderConfirmedMessage incoming) {
        return null;
    }

    public String getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getPayload() {
        return payload;
    }
}
