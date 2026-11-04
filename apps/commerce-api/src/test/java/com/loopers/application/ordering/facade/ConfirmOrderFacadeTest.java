package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.result.PrepareOrderPaymentResult;
import com.loopers.application.ordering.service.OrderPaymentResultService;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.PrepareOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.StartAutomaticPaymentRecoveryUseCase;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.gateway.PaymentProviderGateway.CardInput;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfirmOrderFacadeTest {
    private final PrepareOrderPaymentUseCase preparation = mock(PrepareOrderPaymentUseCase.class);
    private final PaymentProviderGateway gateway = mock(PaymentProviderGateway.class);
    private final RecordPaymentObservationUseCase observations = mock(RecordPaymentObservationUseCase.class);
    private final ApplyOrderPaymentUseCase application = mock(ApplyOrderPaymentUseCase.class);
    private final OrderPaymentResultService results = mock(OrderPaymentResultService.class);
    private final StartAutomaticPaymentRecoveryUseCase automatic = mock(StartAutomaticPaymentRecoveryUseCase.class);
    private final ConfirmOrderFacade facade = new ConfirmOrderFacade(preparation, gateway, observations, application, results, automatic);
    private final ConfirmOrderCommand command = new ConfirmOrderCommand(1L, 1L, UUID.randomUUID().toString(),
        new CardInput("TEST", "0000-0000-0000-0000"), PaymentScenario.resolve(null, null));
    private final PaymentAttempt attempt = PaymentAttempt.create(1L, command.paymentAttemptKey(), 1_000L, command.scenario()).startDispatch();

    @Test
    @DisplayName("준비 commit 뒤 최초 생성·관찰 저장·공통 반영·최신 응답 순서로 조율한다")
    void dispatchesAndRecordsBeforeApplication() {
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, true));
        PaymentObservation pending = PaymentObservation.verified("tx", attempt.getProviderOrderId(), 1_000L, ProviderPaymentStatus.PENDING);
        given(gateway.create(any(), any(), any())).willReturn(pending);
        given(application.execute(attempt.getAttemptKey())).willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.PENDING, false));
        ConfirmOrderResult expected = mock(ConfirmOrderResult.class);
        given(results.read(anyLong(), anyLong(), anyBoolean())).willReturn(expected);

        assertThat(facade.execute(command)).isSameAs(expected);

        var ordered = inOrder(preparation, gateway, observations, application, results, automatic);
        ordered.verify(preparation).execute(command.preparation());
        ordered.verify(gateway).create(any(), any(), any());
        ordered.verify(observations).execute(attempt.getAttemptKey(), pending);
        ordered.verify(application).execute(attempt.getAttemptKey());
        ordered.verify(automatic).execute(attempt.getAttemptKey());
        ordered.verify(results).read(1L, 1L, false);
    }

    @Test
    @DisplayName("기존 시도는 PG 생성 없이 저장 결과를 공통 반영하고 최신 상태를 읽는다")
    void neverRedispatchesExistingAttempt() {
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, false));
        given(application.execute(attempt.getAttemptKey())).willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.APPLIED, true));

        facade.execute(command);

        verifyNoInteractions(gateway, observations, automatic);
        verify(results).read(1L, 1L, true);
    }

    @Test
    @DisplayName("소유권·재고 등 준비 실패는 PG 생성과 이후 반영을 시작하지 않는다")
    void stopsAfterPreparationFailure() {
        given(preparation.execute(any())).willThrow(new IllegalStateException("준비 실패"));
        assertThatThrownBy(() -> facade.execute(command)).hasMessage("준비 실패");
        verifyNoInteractions(gateway, observations, application, results, automatic);
    }

    @Test
    @DisplayName("미완료 재요청도 놓친 READY 자동 작업을 다시 시작하지 않는다")
    void neverStartsAutomaticRecoveryOnReplay() {
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, false));
        given(application.execute(attempt.getAttemptKey())).willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.PENDING, false));
        facade.execute(command);
        verifyNoInteractions(gateway, automatic);
    }
}
