package com.loopers.application.ordering.facade;

import com.loopers.application.ordering.command.HandlePaymentCallbackCommand;
import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.result.PaymentCallbackContext;
import com.loopers.application.ordering.result.PaymentCallbackResult;
import com.loopers.application.ordering.service.OrderPaymentResultService;
import com.loopers.application.ordering.service.ResolvePaymentCallbackService;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.HandlePaymentCallbackUseCase;
import com.loopers.application.pay.gateway.PaymentLookupGateway;
import com.loopers.application.pay.gateway.PaymentLookupGateway.LookupRequest;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class HandlePaymentCallbackFacade implements HandlePaymentCallbackUseCase {
    private final ResolvePaymentCallbackService resolver;
    private final PaymentLookupGateway gateway;
    private final RecordPaymentObservationUseCase observations;
    private final ApplyOrderPaymentUseCase application;
    private final OrderPaymentResultService results;

    @Override
    public PaymentCallbackResult execute(HandlePaymentCallbackCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("callback 권위 확인은 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
        PaymentCallbackContext context = resolver.resolve(command);
        PaymentAttempt attempt = context.attempt();
        // 저장된 키가 없으면 주문별 조회로 거래를 확인한다. 알림 키를 조회/결합의 권위로 사용하지 않는다.
        PaymentObservation observation = gateway.lookup(LookupRequest.from(attempt));
        if (observation.kind() == PaymentObservation.Kind.VERIFIED
                && !command.transactionKey().equals(observation.transactionKey())) {
            throw new ApplicationException(ApplicationErrorCode.PAYMENT_CALLBACK_CONFLICT);
        }
        observations.execute(attempt.getAttemptKey(), observation);
        ApplyOrderPaymentResult applied = application.execute(attempt.getAttemptKey(), context.expectedExecution());
        ConfirmOrderResult current = results.read(context.ownerUserId(), attempt.getOrderId(), applied.attentionRequired());
        return new PaymentCallbackResult(current.completed(), current.attentionRequired());
    }
}
