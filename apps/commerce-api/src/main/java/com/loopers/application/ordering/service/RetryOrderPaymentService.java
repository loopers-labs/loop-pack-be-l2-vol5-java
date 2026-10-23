package com.loopers.application.ordering.service;

import com.loopers.application.ordering.gateway.PaymentApplicationFailureClassifier;
import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.support.PaymentApplicationFailureException;
import com.loopers.application.ordering.support.PaymentApplicationTransaction;
import com.loopers.application.ordering.support.RolledBackPaymentDeadlockException;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryOrderPaymentService {
    private final ApplyOrderPaymentService application;
    private final PaymentApplicationFailureClassifier failures;

    @Retry(name = "payment-application")
    public Optional<ApplyOrderPaymentResult> apply(String key) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("업무 재시도와 대기는 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
        var transaction = new PaymentApplicationTransaction();
        try {
            return application.apply(key, transaction);
        } catch (RuntimeException failure) {
            if (transaction.isRollbackConfirmed() && failures.isDeadlock(failure)) {
                log.info("MySQL 데드락으로 업무 TX가 rollback됐습니다. 제한된 재시도 대상입니다. attemptKey={}", key);
                throw new RolledBackPaymentDeadlockException(failure);
            }
            throw new PaymentApplicationFailureException(failure, transaction.isRollbackConfirmed());
        }
    }
}
