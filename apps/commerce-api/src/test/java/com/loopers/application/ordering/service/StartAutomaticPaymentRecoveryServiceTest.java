package com.loopers.application.ordering.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loopers.application.ordering.result.AutomaticPaymentState;
import com.loopers.application.ordering.usecase.RecoverAutomaticPaymentUseCase;
import com.loopers.application.pay.gateway.PaymentRecoveryExecutor;
import com.loopers.application.pay.gateway.PaymentRecoveryRuntime;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StartAutomaticPaymentRecoveryServiceTest {
    private final AutomaticPaymentStateService states = mock(AutomaticPaymentStateService.class);
    private final RecoverAutomaticPaymentUseCase recovery = mock(RecoverAutomaticPaymentUseCase.class);
    private final StopAutomaticPaymentRecoveryService stopping = mock(StopAutomaticPaymentRecoveryService.class);
    private final PaymentRecoveryExecutor executor = mock(PaymentRecoveryExecutor.class);
    private final PaymentRecoveryRuntime runtime = mock(PaymentRecoveryRuntime.class);
    private final StartAutomaticPaymentRecoveryService service = new StartAutomaticPaymentRecoveryService(states, recovery,
        stopping, executor, runtime, true);
    private final PaymentAttempt attempt = PaymentAttempt.create(1L, UUID.randomUUID().toString(), 1000,
        PaymentScenario.resolve(null, null)).startDispatch();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("명확한 거절은 100ms 뒤 같은 실행으로 전달만 한 번 더 시도하고 재거절은 수동 전환한다")
    void retriesOnlyDefiniteRejection(boolean rejectAgain) throws InterruptedException {
        arrangeClaim();
        var rejected = new PaymentRecoveryExecutor.SubmissionRejectedException(new IllegalStateException("포화"));
        if (rejectAgain) { doThrow(rejected).when(executor).submit(any()); }
        else { doThrow(rejected).doNothing().when(executor).submit(any()); }
        service.execute(attempt.getAttemptKey());
        verify(executor, times(2)).submit(any());
        verify(runtime).pause(100);
        if (rejectAgain) { verify(stopping).stop(any(), eq(PaymentRecoveryTask.Reason.EXECUTOR_REJECTED)); }
        else { verifyNoInteractions(stopping); }
        verifyNoInteractions(recovery);
    }

    @Test
    @DisplayName("접수 여부를 모르는 예외는 재전달하지 않는다")
    void neverResubmitsUncertainAcceptance() {
        arrangeClaim();
        doThrow(new IllegalStateException("접수 미확인")).when(executor).submit(any());
        service.execute(attempt.getAttemptKey());
        verify(executor).submit(any());
        verify(stopping).stop(any(), eq(PaymentRecoveryTask.Reason.DISPATCH_UNCERTAIN));
        verifyNoInteractions(recovery);
    }

    private void arrangeClaim() {
        when(states.claim(eq(attempt.getAttemptKey()), any())).thenAnswer(invocation -> {
            var snapshot = new PaymentRecoveryTask.Snapshot(attempt.getAttemptKey(), PaymentRecoveryTask.State.RUNNING,
                invocation.getArgument(1), 0);
            when(states.read(attempt.getAttemptKey())).thenReturn(new AutomaticPaymentState(OrderStatus.PAYMENT_PENDING, attempt, snapshot));
            return Optional.of(snapshot);
        });
    }
}
