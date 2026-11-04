package com.loopers.application.pay.gateway;

public interface PaymentRecoveryExecutor {
    void submit(Runnable task);

    /** 실행기에 접수되지 않았음을 확실히 아는 거절에만 사용한다. */
    final class SubmissionRejectedException extends RuntimeException {
        public SubmissionRejectedException(RuntimeException cause) { super("자동 조회 실행기가 접수를 거절했습니다.", cause); }
    }
}
