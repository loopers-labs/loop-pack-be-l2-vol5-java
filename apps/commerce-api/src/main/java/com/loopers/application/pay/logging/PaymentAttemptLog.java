package com.loopers.application.pay.logging;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PaymentAttemptLog {
    private final String runId;

    public PaymentAttemptLog(@Value("${payment.lab.run-id:${random.uuid}}") String runId) {
        this.runId = URLEncoder.encode(runId, StandardCharsets.UTF_8);
    }

    public void pgCreate(PaymentAttempt attempt, PaymentObservation observation) {
        String result = switch (observation.kind()) {
            case VERIFIED, REJECTED_NO_EFFECT -> observation.kind().name();
            default -> observation.reason().name();
        };
        log.info("runId={} operation=PG_CREATE result={} orderId={} attemptKey={} providerOrderId={}{}",
            runId, result, attempt.getOrderId(), attempt.getAttemptKey(), attempt.getProviderOrderId(),
            transactionField(observation.transactionKey()));
    }

    public void observationCommitted(PaymentAttempt committed) {
        log.info("runId={} operation=DB_OBSERVATION result=COMMITTED outcome={} orderId={} attemptKey={} providerOrderId={}{}",
            runId, committed.getOutcome(), committed.getOrderId(), committed.getAttemptKey(), committed.getProviderOrderId(),
            transactionField(committed.getTransactionKey()));
    }

    public void observationUnconfirmed(PaymentAttempt attempt, PaymentObservation observation, RuntimeException failure) {
        log.warn("runId={} operation=DB_OBSERVATION result=UNCONFIRMED orderId={} attemptKey={} providerOrderId={}{} cause={}",
            runId, attempt.getOrderId(), attempt.getAttemptKey(), attempt.getProviderOrderId(),
            transactionField(observation.transactionKey()), failure.getClass().getSimpleName());
    }

    private static String transactionField(String key) {
        return key == null ? "" : " transactionKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
    }
}
