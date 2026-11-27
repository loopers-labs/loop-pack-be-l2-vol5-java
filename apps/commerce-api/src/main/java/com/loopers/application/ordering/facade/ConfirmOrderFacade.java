package com.loopers.application.ordering.facade;

import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.result.PrepareOrderPaymentResult;
import com.loopers.application.ordering.service.OrderPaymentResultService;
import com.loopers.application.ordering.usecase.PrepareOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.application.ordering.usecase.StartAutomaticPaymentRecoveryUseCase;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.gateway.PaymentProviderGateway.ExpectedPayment;
import com.loopers.application.pay.logging.PaymentAttemptLog;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
// 짧은 DB TX 사이에서 최초 PG 생성만 한 번 호출한다.
public class ConfirmOrderFacade implements ConfirmOrderUseCase {
    private final PrepareOrderPaymentUseCase preparation;
    private final PaymentProviderGateway gateway;
    private final RecordPaymentObservationUseCase observations;
    private final ApplyOrderPaymentUseCase application;
    private final OrderPaymentResultService results;
    private final StartAutomaticPaymentRecoveryUseCase automatic;
    private final PaymentAttemptRepository attempts;
    private final Clock clock;
    private final PaymentAttemptLog paymentLog;

    @Override
    public ConfirmOrderResult execute(ConfirmOrderCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PG 결제 조율은 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
        PrepareOrderPaymentResult prepared = preparation.execute(command.preparation());
        PaymentAttempt attempt = prepared.attempt();
        if (prepared.dispatchAllowed()) {
            recordDispatchDeadline(attempt);
            dispatchAndRecord(attempt, command.card());
        }
        ApplyOrderPaymentResult applied = application.execute(attempt.getAttemptKey());
        if (prepared.dispatchAllowed() && applied.state() == ApplyOrderPaymentResult.State.PENDING) {
            automatic.execute(attempt.getAttemptKey());
        }
        return results.read(command.userId(), command.orderId(), applied.attentionRequired());
    }

    private void dispatchAndRecord(PaymentAttempt attempt, PaymentProviderGateway.CardInput card) {
        PaymentObservation observation = gateway.create(new ExpectedPayment(attempt.getProviderOrderId(),
            attempt.getAmount(), attempt.getTransactionKey()), card, attempt.getScenario());
        paymentLog.pgCreate(attempt, observation);

        PaymentAttempt committed;
        try {
            committed = observations.execute(attempt.getAttemptKey(), observation);
        } catch (RuntimeException failure) {
            paymentLog.observationUnconfirmed(attempt, observation, failure);
            throw failure;
        }
        // 관찰 TX가 커밋한 상태다. 타임아웃 중 먼저 도착한 콜백이 이미 확정했을 수도 있다.
        paymentLog.observationCommitted(committed);
    }

    private void recordDispatchDeadline(PaymentAttempt attempt) {
        Instant deadline = clock.instant().plus(PaymentProviderGateway.RESPONSE_TIMEOUT).truncatedTo(ChronoUnit.MICROS);
        try {
            attempts.recordResponseDeadline(attempt.getAttemptKey(), deadline);
        } catch (DataAccessException | TransactionException ex) {
            // 최초 발송권은 이미 커밋됐다. 관측용 쓰기가 실패해도 해당 요청을 계속하고 기한은 추정하지 않는다.
            log.warn("PG 응답 기한 저장 결과를 확인할 수 없습니다. orderId={}, attemptKey={}, cause={}",
                attempt.getOrderId(), attempt.getAttemptKey(), ex.getClass().getSimpleName());
        }
    }
}
