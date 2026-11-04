package com.loopers.application.ordering.service;

import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.result.AutomaticPaymentState;
import com.loopers.application.ordering.support.AutomaticPaymentBudgetExceededException;
import com.loopers.application.ordering.support.AutomaticPaymentExecution;
import com.loopers.application.ordering.support.RepeatPaymentLookupException;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.RecoverAutomaticPaymentUseCase;
import com.loopers.application.pay.gateway.PaymentLookupGateway;
import com.loopers.application.pay.gateway.PaymentLookupGateway.LookupRequest;
import com.loopers.application.pay.gateway.PaymentRecoveryRuntime;
import com.loopers.application.pay.service.PaymentRecoveryProgressService;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.policy.PaymentLookupPolicy;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecoverAutomaticPaymentService implements RecoverAutomaticPaymentUseCase {
    private final AutomaticPaymentStateService states;
    private final StopAutomaticPaymentRecoveryService stopping;
    private final PaymentRecoveryRuntime runtime;
    private final PaymentLookupGateway gateway;
    private final RecordPaymentObservationUseCase observations;
    private final ApplyOrderPaymentUseCase application;
    private final PaymentRecoveryProgressService progress;

    @Override
    @Retry(name = "payment-auto-lookup", fallbackMethod = "stopped")
    public void execute(AutomaticPaymentExecution execution) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("자동 조회와 재시도 대기는 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
        try { round(execution); }
        catch (RepeatPaymentLookupException repeat) { throw repeat; }
        catch (RuntimeException failure) {
            log.warn("자동 조회의 상태 확인·저장·반영에 실패했습니다. attemptKey={} executionId={} round={}",
                execution.snapshot().attemptKey(), execution.snapshot().executionId(),
                execution.snapshot().usedRounds(), failure);
            stopping.stop(execution, interrupted() ? PaymentRecoveryTask.Reason.RECOVERY_INTERRUPTED
                : PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR);
        }
    }

    private void round(AutomaticPaymentExecution execution) {
        var expected = execution.snapshot();
        if (finished(execution, states.read(expected.attemptKey())) || outOfTime(execution)) { return; }
        if (expected.usedRounds() >= 4) { stopping.stop(execution, PaymentRecoveryTask.Reason.RETRY_EXHAUSTED); return; }
        execution.beginConsumption();
        if (!states.consume(expected)) {
            if (!finished(execution, states.read(expected.attemptKey()))) {
                stopping.stop(execution, PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR);
            }
            return;
        }
        execution.consumptionCommitted();
        // 새 TX에서 회차 commit 뒤 인계/완료를 확인한 다음, HTTP 직전에 시간을 다시 확인한다.
        AutomaticPaymentState ready = states.read(expected.attemptKey());
        if (finished(execution, ready) || outOfTime(execution)) { return; }
        var held = execution.snapshot();
        log.info("자동 PG 조회 준비. orderId={} attemptKey={} executionId={} round={}",
            ready.attempt().getOrderId(), held.attemptKey(), held.executionId(), held.usedRounds());
        if (outOfTime(execution)) { return; }
        var observation = gateway.lookup(LookupRequest.from(ready.attempt()));
        observations.execute(held.attemptKey(), observation);
        var applied = application.execute(held.attemptKey(), held);
        log.info("자동 PG 조회 반영. attemptKey={} executionId={} round={} kind={} reason={} httpStatus={} applied={}",
            held.attemptKey(), held.executionId(), held.usedRounds(), observation.kind(), observation.reason(),
            observation.httpStatus(), applied.state());
        if (applied.state() == ApplyOrderPaymentResult.State.APPLIED) { return; }
        if (applied.state() == ApplyOrderPaymentResult.State.APPLICATION_FAILED) {
            stopping.stop(execution, PaymentRecoveryTask.Reason.APPLICATION_FAILED); return;
        }
        if (applied.state() == ApplyOrderPaymentResult.State.CONFLICT) {
            stopping.stop(execution, PaymentRecoveryTask.Reason.IDENTITY_CONFLICT); return;
        }
        var reason = PaymentLookupPolicy.incompleteReason(observation);
        if (!PaymentLookupPolicy.retryable(observation)) { stopping.stop(execution, reason); return; }
        if (progress.recordLookup(held, reason)) { throw new RepeatPaymentLookupException(execution); }
    }

    private boolean finished(AutomaticPaymentExecution execution, AutomaticPaymentState state) {
        if (state.completed() || !execution.snapshot().equals(state.task())) { return true; }
        if (state.attempt().getConflict() != PaymentAttempt.Conflict.NONE) {
            stopping.stop(execution, PaymentRecoveryTask.Reason.IDENTITY_CONFLICT); return true;
        }
        if (state.attempt().getOutcome() == PaymentAttempt.Outcome.SUCCESS
                || state.attempt().getOutcome() == PaymentAttempt.Outcome.FAILED) {
            application.execute(execution.snapshot().attemptKey(), execution.snapshot()); return true;
        }
        if (!state.lookupAllowed()) { stopping.stop(execution, PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR); return true; }
        return false;
    }

    private boolean outOfTime(AutomaticPaymentExecution execution) {
        if (interrupted()) { stopping.stop(execution, PaymentRecoveryTask.Reason.RECOVERY_INTERRUPTED); return true; }
        if (execution.expired(runtime.nanoTime())) {
            stopping.stop(execution, PaymentRecoveryTask.Reason.TIME_BUDGET_EXHAUSTED); return true;
        }
        return false;
    }

    private boolean interrupted() { return Thread.currentThread().isInterrupted(); }

    private void stopped(AutomaticPaymentExecution execution, RepeatPaymentLookupException failure) {
        stopping.stop(execution, interrupted() ? PaymentRecoveryTask.Reason.RECOVERY_INTERRUPTED
            : PaymentRecoveryTask.Reason.RETRY_EXHAUSTED);
    }

    private void stopped(AutomaticPaymentExecution execution, AutomaticPaymentBudgetExceededException failure) {
        stopping.stop(execution, PaymentRecoveryTask.Reason.TIME_BUDGET_EXHAUSTED);
    }

    private void stopped(AutomaticPaymentExecution execution, InterruptedException failure) {
        Thread.currentThread().interrupt();
        stopping.stop(execution, PaymentRecoveryTask.Reason.RECOVERY_INTERRUPTED);
    }
}
