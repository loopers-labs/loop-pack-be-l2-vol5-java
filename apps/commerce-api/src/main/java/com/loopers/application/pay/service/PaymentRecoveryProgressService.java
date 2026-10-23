package com.loopers.application.pay.service;

import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentRecoveryProgressService {
    private final PaymentRecoveryTaskRepository tasks;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PaymentRecoveryTask.Snapshot capture(String attemptKey) {
        return tasks.findByKey(attemptKey).orElseThrow(() -> new IllegalStateException("결제 복구 작업이 없습니다."))
            .snapshot();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markApplicationFailed(PaymentRecoveryTask.Snapshot expected) {
        return tasks.markApplicationFailed(expected, UUID.randomUUID().toString(), Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean startManualLookup(PaymentRecoveryTask.Snapshot execution) {
        return tasks.startManualLookup(execution.attemptKey(), execution.executionId(), execution.usedRounds(), Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordLookup(PaymentRecoveryTask.Snapshot execution, PaymentRecoveryTask.Reason reason) {
        return tasks.recordFailure(execution.attemptKey(), execution.executionId(), execution.usedRounds(),
            reason, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean requireManual(PaymentRecoveryTask.Snapshot execution, PaymentRecoveryTask.Reason reason) {
        return tasks.requireManual(execution.attemptKey(), execution.executionId(), execution.usedRounds(), reason, Instant.now());
    }
}
