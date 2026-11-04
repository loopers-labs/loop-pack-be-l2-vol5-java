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
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Override
    public ConfirmOrderResult execute(ConfirmOrderCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PG 결제 조율은 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
        PrepareOrderPaymentResult prepared = preparation.execute(command.preparation());
        PaymentAttempt attempt = prepared.attempt();
        if (prepared.dispatchAllowed()) {
            PaymentObservation observation = gateway.create(new ExpectedPayment(attempt.getProviderOrderId(),
                attempt.getAmount(), attempt.getTransactionKey()), command.card(), attempt.getScenario());
            observations.execute(attempt.getAttemptKey(), observation);
        }
        ApplyOrderPaymentResult applied = application.execute(attempt.getAttemptKey());
        if (prepared.dispatchAllowed() && applied.state() == ApplyOrderPaymentResult.State.PENDING) {
            automatic.execute(attempt.getAttemptKey());
        }
        return results.read(command.userId(), command.orderId(), applied.attentionRequired());
    }
}
