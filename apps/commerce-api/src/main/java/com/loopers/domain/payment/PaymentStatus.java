package com.loopers.domain.payment;

/** 저장한 결제 결과. UNKNOWN 은 발송 권한을 소비했지만 결과를 아직 확인하지 못한 상태다. SUCCESS·FAILED 는 종결이다. */
public enum PaymentStatus {
    UNKNOWN,
    PENDING,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
