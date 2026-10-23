package com.loopers.application.ordering.usecase;

import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.domain.pay.model.PaymentRecoveryTask;

/** 내부 공통 업무 반영 진입점. 호출 경로의 접근·PG 관찰 검증은 진입 전에 끝낸다. */
public interface ApplyOrderPaymentUseCase {
    ApplyOrderPaymentResult execute(String attemptKey);

    ApplyOrderPaymentResult execute(String attemptKey, PaymentRecoveryTask.Snapshot expectedExecution);
}
