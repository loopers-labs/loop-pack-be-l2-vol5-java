package com.loopers.purchase.domain;

/**
 * 이미 완료 기록이 있을 때 들어온 이벤트의 판정 (W6 D-6).
 */
public enum ConsumptionVerdict {
    /** 같은 eventId · 같은 사실 — 정상적인 재전달. DB를 바꾸지 않고 성공 처리(no-op) */
    DUPLICATE,
    /** 같은 eventId인데 사실(fingerprint)이 다르다 */
    CONFLICT_SAME_EVENT_DIFFERENT_FACT,
    /** 같은 주문인데 eventId가 다르다 — 한 주문의 확정 사실이 둘 */
    CONFLICT_SAME_ORDER_DIFFERENT_EVENT
}
