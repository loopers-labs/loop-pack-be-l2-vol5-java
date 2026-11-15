package com.loopers.application.coupon;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 동기 발급 실행 하나의 완료 증거. 조회 결과나 경과 시간으로 TX가 끝났는지 추측하지 않는다. */
class CouponIssueExecution implements TransactionSynchronization {
    private boolean started;
    private int completion = STATUS_UNKNOWN;

    /** 발급 TX 본문의 첫 줄에서 부른다. 이 호출 전에 끝났다면 이 실행의 DB 작업은 없다. */
    void observeTransaction() {
        started = true;
        TransactionSynchronizationManager.registerSynchronization(this);
    }

    @Override
    public void afterCompletion(int status) {
        completion = status;
    }

    /** TX 본문에 들어왔는지. false면 TX 시작(예: DB 연결 획득) 단계에서 끝난 것이다. */
    boolean isStarted() {
        return started;
    }

    /** commit 또는 rollback이 확인됐는지. */
    boolean isCompleted() {
        return completion == STATUS_COMMITTED || completion == STATUS_ROLLED_BACK;
    }
}
