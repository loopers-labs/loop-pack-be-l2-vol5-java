package com.loopers.application.ordering.command;

// 관리자 여부는 요청 본문이 아닌 고객/관리자 Controller 진입점에서 결정한다.
public record ReconcileOrderPaymentCommand(long userId, long orderId, boolean administrator) {
    public ReconcileOrderPaymentCommand {
        if (userId <= 0 || orderId <= 0) { throw new IllegalArgumentException("사용자와 주문 ID는 양수여야 합니다."); }
    }
}
