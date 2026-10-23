package com.loopers.batch.payment;

import com.loopers.batch.payment.PaymentReconciliationRepository.PaymentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.List;

public class ReconcileCancelProcessingTasklet implements Tasklet {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReconcileCancelProcessingTasklet.class);
    private static final int PAGE_SIZE = 100;

    private final PaymentReconciliationRepository repository;
    private final PaymentLookupClient lookupClient;
    private final PaymentStateInterpreter interpreter;

    public ReconcileCancelProcessingTasklet(
        PaymentReconciliationRepository repository,
        PaymentLookupClient lookupClient,
        PaymentStateInterpreter interpreter
    ) {
        this.repository = repository;
        this.lookupClient = lookupClient;
        this.interpreter = interpreter;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        long afterId = 0;
        while (true) {
            List<PaymentRecord> payments = repository.findCancelProcessingPayments(afterId, PAGE_SIZE);
            if (payments.isEmpty()) {
                return RepeatStatus.FINISHED;
            }
            for (PaymentRecord payment : payments) {
                reconcile(payment);
            }
            afterId = payments.getLast().id();
        }
    }

    private void reconcile(PaymentRecord payment) {
        PaymentLookupClient.LookupResult lookup = lookupClient.findByOrderId(payment.providerOrderId());
        RemotePaymentState remoteState = interpreter.interpret(lookup);
        if (remoteState == RemotePaymentState.CANCELED) {
            int updated = repository.markCanceled(payment.id(), lookup.rawResponse());
            LOGGER.info("PG cancellation reconciliation: paymentId={}, applied={}", payment.id(), updated == 1);
            return;
        }
        LOGGER.info("PG cancellation remains unresolved: paymentId={}, remote={}, httpStatus={}",
            payment.id(), remoteState, lookup.httpStatus());
    }
}
