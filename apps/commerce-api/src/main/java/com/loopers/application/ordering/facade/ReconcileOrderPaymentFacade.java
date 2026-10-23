package com.loopers.application.ordering.facade;

import com.loopers.application.ordering.command.ReconcileOrderPaymentCommand;
import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.result.PrepareOrderReconciliationResult;
import com.loopers.application.ordering.service.OrderPaymentResultService;
import com.loopers.application.ordering.service.PrepareOrderReconciliationService;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.ReconcileOrderPaymentUseCase;
import com.loopers.application.pay.gateway.PaymentLookupGateway;
import com.loopers.application.pay.gateway.PaymentLookupGateway.LookupRequest;
import com.loopers.application.pay.service.PaymentRecoveryProgressService;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.policy.PaymentLookupPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileOrderPaymentFacade implements ReconcileOrderPaymentUseCase {
    private final PrepareOrderReconciliationService preparation;
    private final PaymentLookupGateway gateway;
    private final RecordPaymentObservationUseCase observations;
    private final ApplyOrderPaymentUseCase application;
    private final PaymentRecoveryProgressService progress;
    private final OrderPaymentResultService results;

    @Override
    public ConfirmOrderResult execute(ReconcileOrderPaymentCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("수동 대사는 DB 트랜잭션 밖에서 조율해야 합니다.");
        }
        PrepareOrderReconciliationResult prepared = preparation.prepare(command);
        if (prepared.execution() == null) { return current(command, prepared, false); }
        PaymentAttempt attempt = prepared.attempt();
        PaymentRecoveryTask.Reason incompleteReason = PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE;
        if (attempt.getConflict() == PaymentAttempt.Conflict.NONE
                && (attempt.getOutcome() == PaymentAttempt.Outcome.UNKNOWN || attempt.getOutcome() == PaymentAttempt.Outcome.PENDING)) {
            if (!progress.startManualLookup(prepared.execution())) { return current(command, prepared, false); }
            PaymentObservation observation = gateway.lookup(LookupRequest.from(attempt));
            observations.execute(attempt.getAttemptKey(), observation);
            incompleteReason = PaymentLookupPolicy.incompleteReason(observation);
        }
        ApplyOrderPaymentResult applied = application.execute(attempt.getAttemptKey(), prepared.execution());
        boolean attention = applied.attentionRequired();
        if (applied.state() == ApplyOrderPaymentResult.State.PENDING || applied.state() == ApplyOrderPaymentResult.State.CONFLICT) {
            PaymentRecoveryTask.Reason reason = applied.state() == ApplyOrderPaymentResult.State.CONFLICT
                ? PaymentRecoveryTask.Reason.IDENTITY_CONFLICT : incompleteReason;
            try {
                progress.requireManual(prepared.execution(), reason);
            } catch (RuntimeException failure) {
                log.warn("수동 대사 진행을 저장하지 못했습니다. 현재 상태를 다시 확인합니다. attemptKey={}",
                    attempt.getAttemptKey(), failure);
            }
            attention = true;
        }
        return current(command, prepared, attention);
    }

    private ConfirmOrderResult current(ReconcileOrderPaymentCommand command, PrepareOrderReconciliationResult prepared,
                                       boolean attention) {
        return results.read(prepared.ownerUserId(), command.orderId(), attention);
    }
}
