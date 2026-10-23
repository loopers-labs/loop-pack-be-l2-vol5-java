package com.loopers.application.ordering.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 한 번의 업무 TX 전용 메모리 증거. 다음 시도와 공유하거나 DB 실행 권한으로 사용하지 않는다. */
public final class PaymentApplicationTransaction {
    private boolean registered;
    private boolean rollbackConfirmed;

    public void register() {
        if (registered || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("새 업무 트랜잭션 안에서 완료 확인을 등록해야 합니다.");
        }
        registered = true;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                rollbackConfirmed = status == STATUS_ROLLED_BACK;
            }
        });
    }

    public boolean isRollbackConfirmed() { return rollbackConfirmed; }
}
