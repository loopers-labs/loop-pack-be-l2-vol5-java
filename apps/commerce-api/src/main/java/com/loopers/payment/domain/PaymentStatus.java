package com.loopers.payment.domain;

/**
 * 결제 시도 상태 (plan.md 3-2). 종결은 SUCCESS·FAILED 둘이고, 종결은 덮어쓰지 않는다.
 */
public enum PaymentStatus {
    /** 저장됨. 아직 아무도 발송 권한을 갖지 않았다 */
    REQUESTED,
    /** 발송 권한을 얻었다. 실제 발송 여부는 모를 수 있다 */
    SENDING,
    /** PG가 거래를 만들고 처리 중이라고 답했다 (transactionKey 있음) */
    PENDING,
    /** 보냈을 수 있으나 결과를 모른다. 조회로만 종결한다 */
    UNKNOWN,
    SUCCESS,
    FAILED;

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED;
    }
}
