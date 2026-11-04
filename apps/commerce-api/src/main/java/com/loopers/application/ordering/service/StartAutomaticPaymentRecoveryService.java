package com.loopers.application.ordering.service;

import com.loopers.application.ordering.support.AutomaticPaymentExecution;
import com.loopers.application.ordering.usecase.RecoverAutomaticPaymentUseCase;
import com.loopers.application.ordering.usecase.StartAutomaticPaymentRecoveryUseCase;
import com.loopers.application.pay.gateway.PaymentRecoveryExecutor;
import com.loopers.application.pay.gateway.PaymentRecoveryExecutor.SubmissionRejectedException;
import com.loopers.application.pay.gateway.PaymentRecoveryRuntime;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class StartAutomaticPaymentRecoveryService implements StartAutomaticPaymentRecoveryUseCase {
    private final AutomaticPaymentStateService states;
    private final RecoverAutomaticPaymentUseCase recovery;
    private final StopAutomaticPaymentRecoveryService stopping;
    private final PaymentRecoveryExecutor executor;
    private final PaymentRecoveryRuntime runtime;
    private final boolean enabled;

    public StartAutomaticPaymentRecoveryService(AutomaticPaymentStateService states, RecoverAutomaticPaymentUseCase recovery,
            StopAutomaticPaymentRecoveryService stopping, PaymentRecoveryExecutor executor, PaymentRecoveryRuntime runtime,
            @Value("${payment.recovery.auto-start-enabled:true}") boolean enabled) {
        this.states = states;
        this.recovery = recovery;
        this.stopping = stopping;
        this.executor = executor;
        this.runtime = runtime;
        this.enabled = enabled;
    }

    @Override
    public void execute(String attemptKey) {
        if (!enabled) { return; }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("자동 조회 전달은 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
        String key = PaymentAttempt.normalizeKey(attemptKey);
        String id = UUID.randomUUID().toString();
        PaymentRecoveryTask.Snapshot claimed;
        try {
            var result = states.claim(key, id);
            if (result.isEmpty()) { return; }
            claimed = result.get();
        } catch (RuntimeException failure) {
            log.warn("자동 조회 권한 commit을 확인하지 못했습니다. attemptKey={} executionId={}", key, id, failure);
            stopping.stop(new AutomaticPaymentExecution(new PaymentRecoveryTask.Snapshot(key,
                PaymentRecoveryTask.State.RUNNING, id, 0), runtime.nanoTime()), PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR);
            return;
        }
        // 첫 전달 직전에 시작한다. 큐 대기·접수 재시도·조회·Retry 대기가 이 예산에 포함된다.
        var execution = new AutomaticPaymentExecution(claimed, runtime.nanoTime());
        Runnable job = () -> {
            try { recovery.execute(execution); }
            catch (RuntimeException failure) {
                log.warn("자동 조회 실행이 중단됐습니다. attemptKey={} executionId={}", key, id, failure);
                stopping.stop(execution, Thread.currentThread().isInterrupted()
                    ? PaymentRecoveryTask.Reason.RECOVERY_INTERRUPTED : PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR);
            }
        };
        try { executor.submit(job); }
        catch (SubmissionRejectedException rejected) { resubmit(execution, job); }
        catch (RuntimeException uncertain) { uncertain(execution, uncertain); }
    }

    private void resubmit(AutomaticPaymentExecution execution, Runnable job) {
        try {
            long wait = Math.min(100, execution.remainingMillis(runtime.nanoTime()));
            if (wait > 0) { runtime.pause(wait); }
            if (wait < 100 || execution.expired(runtime.nanoTime())) {
                stopping.stop(execution, PaymentRecoveryTask.Reason.TIME_BUDGET_EXHAUSTED); return;
            }
            var state = states.read(execution.snapshot().attemptKey());
            if (state.completed() || !execution.snapshot().equals(state.task())) { return; }
            if (execution.expired(runtime.nanoTime())) {
                stopping.stop(execution, PaymentRecoveryTask.Reason.TIME_BUDGET_EXHAUSTED); return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopping.stop(execution, PaymentRecoveryTask.Reason.RECOVERY_INTERRUPTED);
            return;
        } catch (RuntimeException failure) {
            log.warn("재접수 전 상태를 확인하지 못했습니다. attemptKey={}", execution.snapshot().attemptKey(), failure);
            stopping.stop(execution, PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR);
            return;
        }
        try { executor.submit(job); }
        catch (SubmissionRejectedException rejected) { stopping.stop(execution, PaymentRecoveryTask.Reason.EXECUTOR_REJECTED); }
        catch (RuntimeException uncertain) { uncertain(execution, uncertain); }
    }

    private void uncertain(AutomaticPaymentExecution execution, RuntimeException failure) {
        log.warn("자동 조회 재접수는 하지 않습니다. 접수/상태 확인 결과가 불명확합니다. attemptKey={} executionId={}",
            execution.snapshot().attemptKey(), execution.snapshot().executionId(), failure);
        stopping.stop(execution, PaymentRecoveryTask.Reason.DISPATCH_UNCERTAIN);
    }
}
