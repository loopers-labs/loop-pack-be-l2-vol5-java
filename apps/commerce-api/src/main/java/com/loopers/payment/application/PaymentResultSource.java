package com.loopers.payment.application;

/**
 * 결과 적용 경계를 부른 경로 (W10 plan.md D-5). 경계 로그 ③④⑤의 source로 남아 "이 결제를 누가 종결했나"에 답한다.
 */
public enum PaymentResultSource {
    /** 발송 풀의 생성 응답·발송 실패·풀 거절 */
    DISPATCH,
    /** PG callback */
    CALLBACK,
    /** 고객의 결제 확인 API (POST /api/v1/orders/{orderId}/payment/reconcile) */
    RECONCILE_API,
    /** 자동 reconcile·만료 스케줄러 */
    SCHEDULER,
    /** 멈춘 발송 정리 스케줄러 */
    DISPATCH_CLEANUP
}
