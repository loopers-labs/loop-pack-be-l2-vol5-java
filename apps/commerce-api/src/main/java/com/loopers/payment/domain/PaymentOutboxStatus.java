package com.loopers.payment.domain;

/**
 * 발송 장부 상태 (plan.md 5-1, ADR-W5-01). PENDING에서 claim(발송)과 close(정리)가 조건부 UPDATE로 경쟁한다.
 */
public enum PaymentOutboxStatus {
    /** 저장됨. 아직 아무도 발송 권한을 갖지 않았다 */
    PENDING,
    /** 발송 권한을 얻었다 (claim) */
    CLAIMED,
    /** 발송 뒤 결과를 기록했다 */
    DONE,
    /** 보내지 않고 닫았다 (ADR-W5-03) */
    CLOSED
}
