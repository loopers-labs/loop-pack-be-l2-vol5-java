package com.loopers.application.coupon;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** 한 TX 호출의 종료를 콜백 진입과 afterCompletion 통지로 관찰한다. 예외 종류만으로는 commit 여부를 알 수 없어서다. */
final class ObservedTransaction<T> {

    private boolean entered;
    private Integer completion;
    private boolean returned;
    private T candidate;
    private Throwable failure;

    private ObservedTransaction() {}

    /** 업무 SQL 전에 afterCompletion 을 등록한다. 예외는 던지지 않고 기록한다. */
    static <T> ObservedTransaction<T> run(TransactionTemplate template, Supplier<T> work) {
        ObservedTransaction<T> call = new ObservedTransaction<>();
        try {
            template.execute(status -> {
                call.entered = true;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completionStatus) {
                        call.completion = completionStatus;
                    }
                });
                T value = work.get();
                call.candidate = value;
                return value;
            });
            call.returned = true;
        } catch (RuntimeException | Error e) {
            call.failure = e;
        }
        return call;
    }

    boolean returned() {
        return returned;
    }

    boolean entered() {
        return entered;
    }

    T candidate() {
        return candidate;
    }

    Throwable failure() {
        return failure;
    }

    /** 콜백에 들어간 뒤 UNKNOWN 이거나 완료 통지가 없으면 종료 불명이다. */
    boolean unknown() {
        return !returned && entered
            && (completion == null || completion == TransactionSynchronization.STATUS_UNKNOWN);
    }

    boolean committed() {
        return returned || (entered && completion != null && completion == TransactionSynchronization.STATUS_COMMITTED);
    }

    boolean rolledBack() {
        return !returned && entered && completion != null && completion == TransactionSynchronization.STATUS_ROLLED_BACK;
    }
}
