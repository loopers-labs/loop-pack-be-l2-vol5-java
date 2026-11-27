package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
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
import com.loopers.application.pay.logging.PaymentAttemptLog;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

class ConfirmOrderFacadeTest {
    private final PrepareOrderPaymentUseCase preparation = mock(PrepareOrderPaymentUseCase.class);
    private final PaymentProviderGateway gateway = mock(PaymentProviderGateway.class);
    private final RecordPaymentObservationUseCase observations = mock(RecordPaymentObservationUseCase.class);
    private final ApplyOrderPaymentUseCase application = mock(ApplyOrderPaymentUseCase.class);
    private final OrderPaymentResultService results = mock(OrderPaymentResultService.class);
    private final StartAutomaticPaymentRecoveryUseCase automatic = mock(StartAutomaticPaymentRecoveryUseCase.class);
    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-27T01:00:00.123456789Z"), ZoneOffset.UTC);
    private final ConfirmOrderFacade facade = new ConfirmOrderFacade(
        preparation, gateway, observations, application, results, automatic, attempts, clock, new PaymentAttemptLog("test-run"));
    private final ConfirmOrderCommand command = new ConfirmOrderCommand(1L, 1L, UUID.randomUUID().toString(),
        new CardInput("TEST", "0000-0000-0000-0000"), PaymentScenario.resolve(null, null));
    private final PaymentAttempt attempt = PaymentAttempt.create(1L, command.paymentAttemptKey(), 1_000L, command.scenario()).startDispatch();

    @Test
    @DisplayName("준비 커밋 뒤 응답 기한을 먼저 저장하고 최초 PG 생성과 결과 반영을 진행한다")
    void dispatchesAndRecordsBeforeApplication() {
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, true));
        PaymentObservation pending = PaymentObservation.verified("tx", attempt.getProviderOrderId(), 1_000L, ProviderPaymentStatus.PENDING);
        given(gateway.create(any(), any(), any())).willReturn(pending);
        given(observations.execute(attempt.getAttemptKey(), pending)).willReturn(
            attempt.observe(attempt.getProviderOrderId(), attempt.getAmount(), "tx", PaymentAttempt.Outcome.PENDING));
        given(application.execute(attempt.getAttemptKey())).willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.PENDING, false));
        ConfirmOrderResult expected = mock(ConfirmOrderResult.class);
        given(results.read(anyLong(), anyLong(), anyBoolean())).willReturn(expected);

        assertThat(facade.execute(command)).isSameAs(expected);

        var ordered = inOrder(preparation, attempts, gateway, observations, application, results, automatic);
        ordered.verify(preparation).execute(command.preparation());
        ordered.verify(attempts).recordResponseDeadline(attempt.getAttemptKey(), Instant.parse("2026-11-27T01:00:02.123456Z"));
        ordered.verify(gateway).create(any(), any(), any());
        ordered.verify(observations).execute(attempt.getAttemptKey(), pending);
        ordered.verify(application).execute(attempt.getAttemptKey());
        ordered.verify(automatic).execute(attempt.getAttemptKey());
        ordered.verify(results).read(1L, 1L, false);
    }

    @Test
    @DisplayName("관측용 기한 기록이 일시 실패해도 이미 발송권을 받은 최초 PG 요청을 한 번 실행한다")
    void dispatchesAfterDeadlineWriteFailure() {
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, true));
        doThrow(new DataAccessResourceFailureException("unavailable")).when(attempts).recordResponseDeadline(any(), any());
        PaymentObservation pending = PaymentObservation.verified("tx", attempt.getProviderOrderId(), attempt.getAmount(), ProviderPaymentStatus.PENDING);
        given(gateway.create(any(), any(), any())).willReturn(pending);
        given(observations.execute(attempt.getAttemptKey(), pending)).willReturn(
            attempt.observe(attempt.getProviderOrderId(), attempt.getAmount(), "tx", PaymentAttempt.Outcome.PENDING));
        given(application.execute(attempt.getAttemptKey()))
            .willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.PENDING, false));

        facade.execute(command);

        verify(gateway).create(any(), any(), any());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("PG 생성이 타임아웃돼도 그사이 콜백으로 확정된 결제는 DB 로그에 SUCCESS로 기록한다")
    void logsCommittedOutcomeAfterConcurrentSuccess(CapturedOutput output) {
        PaymentObservation timeout = PaymentObservation.unavailable(PaymentObservation.Reason.TIMEOUT);
        PaymentAttempt confirmed = attempt.observe(attempt.getProviderOrderId(), attempt.getAmount(), "tx-callback", PaymentAttempt.Outcome.SUCCESS);
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, true));
        given(gateway.create(any(), any(), any())).willReturn(timeout);
        given(observations.execute(attempt.getAttemptKey(), timeout)).willReturn(confirmed);
        given(application.execute(attempt.getAttemptKey())).willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.APPLIED, false));

        facade.execute(command);

        var evidence = output.getAll().lines().filter(line -> line.contains("attemptKey=" + attempt.getAttemptKey())).toList();
        assertThat(evidence).satisfiesExactly(
            line -> assertThat(line).contains("operation=PG_CREATE ", "result=TIMEOUT"),
            line -> assertThat(line).contains("operation=DB_OBSERVATION ", "result=COMMITTED", "outcome=SUCCESS",
                "transactionKey=tx-callback"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("DB 커밋 결과가 불분명하면 확인 불가로 기록하고 원래 예외를 전달한 뒤 후속 처리를 중단한다")
    void logsUnconfirmedWhenObservationCommitFails(CapturedOutput output) {
        PaymentObservation timeout = PaymentObservation.unavailable(PaymentObservation.Reason.TIMEOUT);
        TransactionSystemException failure = new TransactionSystemException("commit acknowledgement unavailable");
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, true));
        given(gateway.create(any(), any(), any())).willReturn(timeout);
        given(observations.execute(attempt.getAttemptKey(), timeout)).willThrow(failure);

        assertThatThrownBy(() -> facade.execute(command)).isSameAs(failure);

        var evidence = output.getAll().lines().filter(line -> line.contains("attemptKey=" + attempt.getAttemptKey())).toList();
        assertThat(evidence).satisfiesExactly(
            line -> assertThat(line).contains("operation=PG_CREATE ", "result=TIMEOUT"),
            line -> assertThat(line).contains("operation=DB_OBSERVATION ", "result=UNCONFIRMED").doesNotContain("result=COMMITTED"));
        verifyNoInteractions(application, automatic, results);
    }

    @Test
    @DisplayName("기존 시도는 PG 생성 없이 저장 결과를 공통 반영하고 최신 상태를 읽는다")
    void neverRedispatchesExistingAttempt() {
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, false));
        given(application.execute(attempt.getAttemptKey())).willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.APPLIED, true));

        facade.execute(command);

        verifyNoInteractions(attempts, gateway, observations, automatic);
        verify(results).read(1L, 1L, true);
    }

    @Test
    @DisplayName("소유권·재고 등 준비 실패는 PG 생성과 이후 반영을 시작하지 않는다")
    void stopsAfterPreparationFailure() {
        given(preparation.execute(any())).willThrow(new IllegalStateException("준비 실패"));
        assertThatThrownBy(() -> facade.execute(command)).hasMessage("준비 실패");
        verifyNoInteractions(attempts, gateway, observations, application, results, automatic);
    }

    @Test
    @DisplayName("미완료 재요청도 놓친 READY 자동 작업을 다시 시작하지 않는다")
    void neverStartsAutomaticRecoveryOnReplay() {
        given(preparation.execute(any())).willReturn(new PrepareOrderPaymentResult(attempt, false));
        given(application.execute(attempt.getAttemptKey())).willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.PENDING, false));
        facade.execute(command);
        verifyNoInteractions(attempts, gateway, automatic);
    }
}
