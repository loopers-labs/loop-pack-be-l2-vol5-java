package com.loopers.application.payment;

public class PaymentLookupNotCalledException extends RuntimeException {
    public PaymentLookupNotCalledException() {
        super("결제 조회 회로가 열려 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
