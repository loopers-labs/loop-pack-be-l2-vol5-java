package com.loopers.domain.purchase;

/** 완료 기록의 식별자가 경쟁했다는 신호. 동일 사실인지는 rollback 뒤 확인해야 한다. */
public class DuplicateOrderConfirmationException extends RuntimeException {
    public DuplicateOrderConfirmationException(Throwable cause) {
        super("주문 확정 완료 기록의 식별자가 이미 존재합니다.", cause);
    }
}
