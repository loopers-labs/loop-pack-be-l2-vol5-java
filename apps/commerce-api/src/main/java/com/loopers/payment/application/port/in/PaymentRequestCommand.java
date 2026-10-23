package com.loopers.payment.application.port.in;

/**
 * 주문 확정(PG 결제 요청) 입력 (plan.md 2·8). attemptKey는 클라이언트의 Idempotency-Key다.
 * 카드 번호는 PG 요청까지 메모리로만 전달하고 저장·로그하지 않는다 — toString에서도 뺀다.
 */
public record PaymentRequestCommand(Long userId, Long orderId, String attemptKey, String cardType, String cardNo) {

    @Override
    public String toString() {
        return "PaymentRequestCommand[userId=" + userId + ", orderId=" + orderId + ", attemptKey=" + attemptKey + ", cardType=" + cardType + "]";
    }
}
