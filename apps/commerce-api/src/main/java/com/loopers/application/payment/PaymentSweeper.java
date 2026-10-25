package com.loopers.application.payment;

import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSweeper {

    private static final Duration SETTLING_TIME = Duration.ofSeconds(10);
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(2);
    private static final int BATCH = 50;

    private final PaymentTransactions paymentTransactions;
    private final PaymentGateway paymentGateway;
    private final PaymentMetrics paymentMetrics;

    public int sweepOnce(Instant now) {
        List<Payment> due = paymentTransactions.pickDueForCheck(
            now.minus(SETTLING_TIME), now.minus(CHECK_INTERVAL), BATCH, now);
        due.forEach(payment -> check(payment, now));
        return due.size();
    }

    private void check(Payment payment, Instant now) {
        LookupResult result = payment.getTransactionKey() != null
            ? paymentGateway.lookupRetrying(payment.getTransactionKey())
            : paymentGateway.lookupRetrying(payment.getProviderOrderId());
        paymentMetrics.lookedUp("sweeper", result);
        if (!(result instanceof LookupResult.Observation observation)) {
            return;
        }
        try {
            paymentTransactions.observe(payment.getId(), observation, PaymentDiscrepancy.Source.LOOKUP, now);
        } catch (DataAccessException e) {
            log.warn("sweeper 가 조회 결과를 반영하지 못했다. 다음 주기에 다시 본다: paymentId={}", payment.getId(), e);
        }
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "payment.sweeper.enabled", havingValue = "true")
    @RequiredArgsConstructor
    static class Schedule {

        private final PaymentSweeper sweeper;

        @Scheduled(fixedDelayString = "${payment.sweeper.interval:10s}")
        void sweep() {
            sweeper.sweepOnce(Instant.now());
        }
    }
}
