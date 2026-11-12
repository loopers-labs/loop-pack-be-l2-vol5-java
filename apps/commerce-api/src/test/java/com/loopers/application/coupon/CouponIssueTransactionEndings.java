package com.loopers.application.coupon;

import org.mockito.stubbing.Answer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 발급 트랜잭션 mock이 Spring 트랜잭션처럼 완료 상태를 알린 뒤 예외를 던지게 한다.
 */
final class CouponIssueTransactionEndings {

    private CouponIssueTransactionEndings() {
    }

    /**
     * 발급 트랜잭션 안에서 완료 상태를 기록하게 한 뒤, Spring이 commit 요청과 완료 상태를 알리는 순서를 재현한다.
     */
    static Answer<CouponIssueInfo> endsWith(boolean commitRequested, Integer status, Throwable failure) {
        return invocation -> {
            CouponIssueTransactionCompletion completion = invocation.getArgument(2);
            TransactionSynchronizationManager.initSynchronization();
            try {
                completion.watchCurrentTransaction();
                List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
                if (commitRequested) {
                    synchronizations.forEach(synchronization -> synchronization.beforeCommit(false));
                }
                if (status != null) {
                    synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
                }
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            throw failure;
        };
    }
}
