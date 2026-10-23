package com.loopers.domain.payment;

// 같은 시도 키나 같은 주문의 시도가 이미 저장된 경우다. 결제 요청은 rollback 뒤 저장된 시도로 재요청을 판정한다.
public class DuplicatePaymentAttemptException extends RuntimeException {

    public DuplicatePaymentAttemptException(Throwable cause) {
        super("이미 저장된 결제 시도와 충돌합니다.", cause);
    }
}
