package com.loopers.payment.domain;

/**
 * 결제 시도를 저장한 TX A가 commit되면 발송을 시작하라는 신호 (ADR-W5-01).
 * 카드 정보는 이 메모리 객체로만 발송 스레드에 전달된다 — 저장·로그하지 않으므로 toString에서 뺀다 (plan.md 8).
 */
public record PaymentRequestedEvent(Long paymentId, String cardType, String cardNo) {

    @Override
    public String toString() {
        return "PaymentRequestedEvent[paymentId=" + paymentId + ", cardType=" + cardType + "]";
    }
}
