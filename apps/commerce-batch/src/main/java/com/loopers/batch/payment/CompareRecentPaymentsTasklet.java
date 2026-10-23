package com.loopers.batch.payment;

import com.loopers.batch.payment.PaymentReconciliationRepository.PaymentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

public class CompareRecentPaymentsTasklet implements Tasklet {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompareRecentPaymentsTasklet.class);
    private static final int PAGE_SIZE = 100;

    private final PaymentReconciliationRepository repository;
    private final PaymentLookupClient lookupClient;
    private final PaymentStateInterpreter interpreter;
    private final Clock clock;

    public CompareRecentPaymentsTasklet(
        PaymentReconciliationRepository repository,
        PaymentLookupClient lookupClient,
        PaymentStateInterpreter interpreter,
        Clock clock
    ) {
        this.repository = repository;
        this.lookupClient = lookupClient;
        this.interpreter = interpreter;
        this.clock = clock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDateTime until = LocalDateTime.now(clock);
        LocalDateTime from = until.minusMinutes(10);
        long afterId = 0;
        while (true) {
            List<PaymentRecord> payments = repository.findRecentTerminalPayments(from, until, afterId, PAGE_SIZE);
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
        String reconciliationStatus;
        if (remoteState == RemotePaymentState.UNKNOWN || remoteState == RemotePaymentState.PROCESSING) {
            reconciliationStatus = "UNRESOLVED";
        } else if (payment.status().equals(remoteState.name())) {
            reconciliationStatus = "MATCHED";
        } else {
            reconciliationStatus = "MISMATCH";
        }

        repository.recordReconciliation(payment, reconciliationStatus, lookup.paymentStatus(), lookup.rawResponse(),
            LocalDateTime.now(clock));

        if ("MISMATCH".equals(reconciliationStatus)) {
            LOGGER.warn("PG payment discrepancy requires review: paymentId={}, local={}, remote={}, httpStatus={}",
                payment.id(), payment.status(), remoteState, lookup.httpStatus());
        }
    }
}
