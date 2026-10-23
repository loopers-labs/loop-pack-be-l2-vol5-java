package com.loopers.application.order.dto;

import com.loopers.domain.order.PaymentResult;

/** 주문 확정 때의 결제 결과. 결제한 금액이다. 포인트 잔액은 포인트 조회로 본다. */
public record PaymentInfo(long paidAmount) {
    /** 확정 전(DRAFT)에는 결제 결과가 없으므로 {@code null} 을 받으면 {@code null} 을 돌려준다. */
    public static PaymentInfo from(PaymentResult result) {
        if (result == null) {
            return null;
        }
        return new PaymentInfo(result.paidAmount().amount());
    }
}
