package com.loopers.domain.purchase;

public class DuplicateOrderConfirmationException extends RuntimeException {
    public DuplicateOrderConfirmationException(Throwable cause) {
        super("동시에 저장된 주문 확정 기록이 있습니다.", cause);
    }
}
