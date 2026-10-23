package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.result.AutomaticPaymentState;
import com.loopers.application.ordering.support.AutomaticPaymentExecution;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.pay.gateway.PaymentLookupGateway;
import com.loopers.application.pay.gateway.PaymentRecoveryRuntime;
import com.loopers.application.pay.service.PaymentRecoveryProgressService;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoverAutomaticPaymentServiceTest {
    private final AutomaticPaymentStateService states = mock(AutomaticPaymentStateService.class);
    private final StopAutomaticPaymentRecoveryService stopping = mock(StopAutomaticPaymentRecoveryService.class);
    private final PaymentRecoveryRuntime runtime = mock(PaymentRecoveryRuntime.class);
    private final PaymentLookupGateway gateway = mock(PaymentLookupGateway.class);
    private final RecordPaymentObservationUseCase observations = mock(RecordPaymentObservationUseCase.class);
    private final ApplyOrderPaymentUseCase application = mock(ApplyOrderPaymentUseCase.class);
    private final PaymentRecoveryProgressService progress = mock(PaymentRecoveryProgressService.class);
    private final RecoverAutomaticPaymentService service = new RecoverAutomaticPaymentService(states, stopping, runtime,
        gateway, observations, application, progress);
    private final PaymentAttempt attempt = PaymentAttempt.create(1L, UUID.randomUUID().toString(), 1000,
        PaymentScenario.resolve(null, null)).startDispatch();
    private final PaymentRecoveryTask.Snapshot initial = new PaymentRecoveryTask.Snapshot(attempt.getAttemptKey(),
        PaymentRecoveryTask.State.RUNNING, UUID.randomUUID().toString(), 0);
    private final AutomaticPaymentExecution execution = new AutomaticPaymentExecution(initial, 0);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("큐 대기 또는 회차 commit 대기 중 예산이 끝나면 새 HTTP 없이 소비한 회차만 유지한다")
    void stopsAtBudgetBoundary(boolean afterCommit) {
        when(states.read(attempt.getAttemptKey())).thenReturn(state(initial), state(next(initial.executionId())));
        when(states.consume(initial)).thenReturn(true);
        when(runtime.nanoTime()).thenReturn(afterCommit ? 0L : 31_000_000_000L, 31_000_000_000L);
        service.execute(execution);
        assertThat(execution.snapshot().usedRounds()).isEqualTo(afterCommit ? 1 : 0);
        verify(stopping).stop(execution, PaymentRecoveryTask.Reason.TIME_BUDGET_EXHAUSTED);
        verifyNoInteractions(gateway, observations, application);
    }

    @Test
    @DisplayName("회차 commit 뒤 인계되면 옛 작업은 HTTP나 새 실행의 수동 표시를 시작하지 않는다")
    void stopsAfterPostCommitHandover() {
        when(states.read(attempt.getAttemptKey())).thenReturn(state(initial), state(next(UUID.randomUUID().toString())));
        when(states.consume(initial)).thenReturn(true);
        service.execute(execution);
        assertThat(execution.snapshot().usedRounds()).isEqualTo(1);
        verifyNoInteractions(gateway, observations, application, stopping);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("회차 commit 또는 commit 뒤 상태 확인이 실패하면 PG 재조회로 우회하지 않는다")
    void stopsWhenAccountingCannotBeConfirmed(boolean committed) {
        when(states.read(attempt.getAttemptKey())).thenReturn(state(initial));
        if (committed) {
            when(states.consume(initial)).thenReturn(true);
            when(states.read(attempt.getAttemptKey())).thenReturn(state(initial))
                .thenThrow(new IllegalStateException("상태 조회 실패"));
        } else { when(states.consume(initial)).thenThrow(new IllegalStateException("commit 미확인")); }
        service.execute(execution);
        assertThat(execution.snapshotFor(next(initial.executionId()))).isNotNull();
        verify(stopping).stop(execution, PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR);
        verifyNoInteractions(gateway, observations, application);
    }

    @Test
    @DisplayName("이미 시작한 조회가 예산 뒤 성공하면 관찰 저장과 업무 반영까지 마무리한다")
    void finishesInFlightResultAfterBudget() {
        when(states.read(attempt.getAttemptKey())).thenReturn(state(initial), state(next(initial.executionId())));
        when(states.consume(initial)).thenReturn(true);
        when(gateway.lookup(any())).thenAnswer(invocation -> {
            when(runtime.nanoTime()).thenReturn(31_000_000_000L);
            return PaymentObservation.verified("PG-1", attempt.getProviderOrderId(), 1000, ProviderPaymentStatus.SUCCESS);
        });
        when(application.execute(attempt.getAttemptKey(), next(initial.executionId())))
            .thenReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.APPLIED, false));
        service.execute(execution);
        verify(observations).execute(any(), any());
        verify(application).execute(attempt.getAttemptKey(), next(initial.executionId()));
        verifyNoInteractions(stopping);
    }

    private AutomaticPaymentState state(PaymentRecoveryTask.Snapshot task) {
        return new AutomaticPaymentState(OrderStatus.PAYMENT_PENDING, attempt, task);
    }

    private PaymentRecoveryTask.Snapshot next(String id) {
        return new PaymentRecoveryTask.Snapshot(attempt.getAttemptKey(), PaymentRecoveryTask.State.RUNNING, id, 1);
    }
}
