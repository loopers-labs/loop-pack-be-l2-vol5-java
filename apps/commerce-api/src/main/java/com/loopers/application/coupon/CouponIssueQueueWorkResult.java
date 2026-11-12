package com.loopers.application.coupon;

/**
 * worker가 한 번 처리를 시도한 결과. 실행 구조는 이 값으로 바로 이어서 처리할지, 기다릴지, 멈출지 정한다.
 */
public enum CouponIssueQueueWorkResult {
    /** 가져올 대기 작업이 없거나 대기열을 읽지 못했다. 다음 확인 주기를 기다린다. */
    IDLE,
    /** 실행 자리가 없어 작업을 건드리지 않았다. 다음 확인 주기를 기다린다. */
    NO_PERMIT,
    /** 작업 하나를 처리하고 결과를 기록했다. 바로 다음 작업을 확인한다. */
    PROCESSED,
    /** 신규 수용이 닫혀 있어 permit을 요청하지 않았다. */
    ADMISSION_CLOSED,
    /** 결과를 확인할 수 없는 오류로 신규 수용을 닫았다. 수동 확인 전까지 추가로 가져오지 않는다. */
    STOPPED
}
