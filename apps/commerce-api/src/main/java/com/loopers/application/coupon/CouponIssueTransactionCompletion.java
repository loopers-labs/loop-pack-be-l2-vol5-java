package com.loopers.application.coupon;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 발급 트랜잭션 한 번의 DB 작업이 끝났는지 기록한다.
 * 예외 종류만으로는 commit·rollback이 끝났는지 알 수 없으므로 Spring이 알려 주는 완료 상태를 남긴다.
 */
public class CouponIssueTransactionCompletion {

    private boolean started;
    private boolean commitRequested;
    private Integer completionStatus;

    /**
     * 발급 트랜잭션 메서드의 첫 줄에서 호출한다. 이 호출 전에는 발급 SQL을 실행하지 않는다.
     */
    public void watchCurrentTransaction() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                commitRequested = true;
            }

            @Override
            public void afterCompletion(int status) {
                completionStatus = status;
            }
        });
        started = true;
    }

    /**
     * 발급 SQL을 실행하기 전에 끝났거나, commit이 끝났거나, commit 요청 전에 rollback이 끝났으면 남은 DB 작업이 없다.
     * commit을 요청한 뒤 실패하면 Spring이 rollback으로 알려도 서버의 commit 여부를 알 수 없으므로 끝난 것으로 보지 않는다.
     */
    public boolean isDbWorkDone() {
        if (!started) {
            return true;
        }
        if (completionStatus == null) {
            return false;
        }
        if (completionStatus == TransactionSynchronization.STATUS_COMMITTED) {
            return true;
        }
        return completionStatus == TransactionSynchronization.STATUS_ROLLED_BACK && !commitRequested;
    }
}
