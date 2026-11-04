package com.loopers.application.ordering.service;

import com.loopers.application.ordering.support.AutomaticPaymentExecution;
import com.loopers.application.pay.service.PaymentRecoveryProgressService;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StopAutomaticPaymentRecoveryService {
    private final AutomaticPaymentStateService states;
    private final PaymentRecoveryProgressService progress;

    public void stop(AutomaticPaymentExecution execution, PaymentRecoveryTask.Reason reason) {
        var held = execution.snapshot();
        try {
            var state = states.read(held.attemptKey());
            if (state.completed()) { return; }
            var expected = execution.snapshotFor(state.task());
            if (expected == null) { return; }
            boolean marked = progress.requireManual(expected, reason);
            log.info("자동 결제 조회 중단. attemptKey={} executionId={} round={} reason={} marked={}",
                expected.attemptKey(), expected.executionId(), expected.usedRounds(), reason, marked);
        } catch (RuntimeException failure) {
            log.warn("자동 조회 중단 상태를 확인하거나 표시하지 못했습니다. attemptKey={} executionId={} round={} reason={}",
                held.attemptKey(), held.executionId(), held.usedRounds(), reason, failure);
        }
    }
}
