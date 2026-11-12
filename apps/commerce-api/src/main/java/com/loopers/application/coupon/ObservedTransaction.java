package com.loopers.application.coupon;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 한 TX 호출을 요청 안에서 관찰한 결과(설계 §12.11). 콜백 진입과 afterCompletion 통지로 업무 미시작·commit·rollback·종료 불명을 가른다.
 * 예외 종류보다 이 종료 판정을 먼저 적용하기 위한 값이며 요청 사이에 공유하지 않는다.
 */
final class ObservedTransaction<T> {

    private boolean entered;
    private Integer completion;
    private boolean returned;
    private T candidate;
    private Throwable failure;

    private ObservedTransaction() {}

    /** 콜백 첫 부분에서 진입을 표시하고 업무 SQL 전에 afterCompletion 을 등록한다. 결과는 콜백 안에서 복사한 값이다. 예외는 던지지 않고 기록한다. */
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

    /** execute 가 정상 반환했다. */
    boolean returned() {
        return returned;
    }

    /** 콜백에 들어갔다. false 면 업무 SQL 을 시작하지 않았다. */
    boolean entered() {
        return entered;
    }

    /** 콜백이 반환한 값. 반환 뒤 예외가 있었어도 남는다. */
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
