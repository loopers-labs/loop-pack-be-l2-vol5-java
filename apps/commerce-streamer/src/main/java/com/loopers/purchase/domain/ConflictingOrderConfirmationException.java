package com.loopers.purchase.domain;

/**
 * 같은 신원(eventId 또는 주문)인데 다른 사실 — 정상 흐름에서는 생기면 안 된다 (W6 D-6). 저장하지 않고 드러낸다.
 * 다시 시도해도 같으므로 재시도 없이 DLT로 보낸다 (ADR-W6-06). 두 원문을 함께 남겨 조사할 수 있게 한다.
 */
public class ConflictingOrderConfirmationException extends RuntimeException {

    private final ConsumptionVerdict verdict;

    public ConflictingOrderConfirmationException(ConsumptionVerdict verdict, String existingPayload, String incomingPayload) {
        super("주문 확정 이벤트 충돌: " + verdict + " | 기존=" + existingPayload + " | 받은=" + incomingPayload);
        this.verdict = verdict;
    }

    public ConsumptionVerdict getVerdict() {
        return verdict;
    }
}
