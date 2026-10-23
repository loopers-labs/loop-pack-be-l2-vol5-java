package com.loopers.domain.payment;

/** 결제 시도 상태 (design.md 9-3절). SUCCESS·FAILED는 종결이고, 종결 뒤에는 어떤 관찰도 상태를 바꾸지 않는다. */
public enum PaymentAttemptStatus {
    /** 발송 권한을 얻어 기록했다. 실제로 나갔는지는 모른다. */
    REQUESTED,
    /** PG가 거래를 만들고 처리 중이라고 알려 줬다. */
    PENDING,
    /** 생성 결과를 확인하지 못했다. 거래가 생겼을 수 있다. */
    UNKNOWN,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
