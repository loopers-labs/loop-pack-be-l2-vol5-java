package com.loopers.application.coupon.admission;

/** 당첨 판정 결과. 업무 판단(활성·한도·1인 1장)은 모두 이 판정에서 끝난다. */
public enum ReserveOutcome {
    /** 수용이 닫혔거나 상태를 잃었다. 신규 판정을 하지 않았다. */
    CLOSED,
    /** 이미 발급됐다. couponId 가 함께 온다. */
    ISSUED,
    /** 202 로 약속한 당첨이다. DB 에 당첨 기록이 있다. */
    TICKETED,
    /** 당첨됐지만 아직 약속하지 않았다. 다른 실행이 처리 중일 수 있다. */
    PENDING,
    INACTIVE,
    SOLD_OUT,
    /** 새 당첨이고 실행 자리를 얻었다. */
    WON_RUNNING,
    /** 새 당첨이지만 실행 자리가 없다. */
    WON_WAITING
}
