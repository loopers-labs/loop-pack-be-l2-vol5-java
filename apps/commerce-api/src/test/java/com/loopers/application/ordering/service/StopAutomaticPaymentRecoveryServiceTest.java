package com.loopers.application.ordering.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loopers.application.ordering.result.AutomaticPaymentState;
import com.loopers.application.ordering.support.AutomaticPaymentExecution;
import com.loopers.application.pay.service.PaymentRecoveryProgressService;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StopAutomaticPaymentRecoveryServiceTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("commit 미확인 중단은 원래 실행의 이전/예정 회차만 표시하고 새 실행에는 표시하지 않는다")
    void marksOnlyHeldCandidate(int actual) {
        var states = mock(AutomaticPaymentStateService.class);
        var progress = mock(PaymentRecoveryProgressService.class);
        var service = new StopAutomaticPaymentRecoveryService(states, progress);
        var attempt = PaymentAttempt.create(1L, UUID.randomUUID().toString(), 1000, PaymentScenario.resolve(null, null)).startDispatch();
        String id = UUID.randomUUID().toString();
        var initial = new PaymentRecoveryTask.Snapshot(attempt.getAttemptKey(), PaymentRecoveryTask.State.RUNNING, id, 0);
        var execution = new AutomaticPaymentExecution(initial, 0);
        var reserved = execution.beginConsumption();
        var snapshot = actual == 0 ? initial : actual == 1 ? reserved : new PaymentRecoveryTask.Snapshot(attempt.getAttemptKey(),
            PaymentRecoveryTask.State.RUNNING, UUID.randomUUID().toString(), 1);
        when(states.read(attempt.getAttemptKey())).thenReturn(new AutomaticPaymentState(OrderStatus.PAYMENT_PENDING, attempt, snapshot));
        service.stop(execution, PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR);
        if (actual < 2) { verify(progress).requireManual(snapshot, PaymentRecoveryTask.Reason.RECOVERY_STATE_ERROR); }
        else { verifyNoInteractions(progress); }
    }
}
