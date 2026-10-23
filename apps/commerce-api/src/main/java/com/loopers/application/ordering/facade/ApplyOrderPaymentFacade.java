package com.loopers.application.ordering.facade;

import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.service.ApplyOrderPaymentService;
import com.loopers.application.ordering.service.RetryOrderPaymentService;
import com.loopers.application.ordering.support.PaymentApplicationFailureException;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.pay.service.PaymentRecoveryProgressService;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApplyOrderPaymentFacade implements ApplyOrderPaymentUseCase {
    private final ApplyOrderPaymentService application;
    private final RetryOrderPaymentService retry;
    private final PaymentRecoveryProgressService progress;

    @Override
    public ApplyOrderPaymentResult execute(String attemptKey) {
        requireNoTransaction();
        String key = PaymentAttempt.normalizeKey(attemptKey);
        PaymentRecoveryTask.Snapshot expected = progress.capture(key);
        return execute(key, expected);
    }

    @Override
    public ApplyOrderPaymentResult execute(String attemptKey, PaymentRecoveryTask.Snapshot expected) {
        requireNoTransaction();
        String key = PaymentAttempt.normalizeKey(attemptKey);
        if (expected == null || !key.equals(expected.attemptKey())) {
            throw new IllegalArgumentException("업무 시도 당시의 같은 결제 실행 정보가 필요합니다.");
        }
        Optional<ApplyOrderPaymentResult> applied;
        try {
            applied = retry.apply(key);
        } catch (RuntimeException failure) {
            log.warn("주문 결제 업무 반영에 실패했습니다. attemptKey={}", key, failure);
            if (!(failure instanceof PaymentApplicationFailureException applicationFailure)
                    || !applicationFailure.isRollbackConfirmed()) {
                ApplyOrderPaymentResult current = application.currentResult(key, false);
                if (current.state() == ApplyOrderPaymentResult.State.APPLIED) { return current; }
            }
            try {
                progress.markApplicationFailed(expected);
            } catch (RuntimeException markerFailure) {
                log.warn("업무 실패 표식을 저장하지 못했습니다. 현재 상태를 다시 확인합니다. attemptKey={}", key, markerFailure);
            }
            return application.currentResult(key, true);
        }
        return applied.orElseGet(() -> application.currentResult(key, false));
    }

    private void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("업무 반영 조율은 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
    }
}
