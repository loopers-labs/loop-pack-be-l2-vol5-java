package com.loopers.application.payment;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReconcileOrderPaymentFacade {
    private final OrderPaymentTransactions transactions;
    private final PaymentGateway gateway;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result reconcile(Long userId, long orderId) {
        Payment paymentBeforeLookup = transactions.findForReconciliation(userId, orderId);
        return PaymentLogContext.withPayment(paymentBeforeLookup, () -> reconcile(userId, paymentBeforeLookup));
    }

    private Result reconcile(Long userId, Payment paymentBeforeLookup) {
        long orderId = paymentBeforeLookup.orderId();
        PaymentGateway.ObservedPayment observed;
        try {
            var existingTransactions = gateway.findByOrderId(paymentBeforeLookup.providerOrderId());
            if (existingTransactions.size() != 1) {
                logLookupMatchCount(paymentBeforeLookup, existingTransactions.size());
                // 조회 0건도 아직 도착하지 않은 POST의 부재를 증명하지 못한다.
                return new Result(transactions.recordUnknown(orderId), true);
            }
            observed = existingTransactions.getFirst();
        } catch (PaymentGateway.LookupBlockedException | PaymentGateway.UnconfirmedException failure) {
            PaymentLookupLog.unavailable(paymentBeforeLookup, "order_id", failure);
            // 조회 중 다른 요청이 결제를 반영했을 수 있으므로 현재 저장값을 다시 읽는다.
            return new Result(transactions.findForReconciliation(userId, orderId), false);
        }
        logMatchedPayment(paymentBeforeLookup, observed.status());
        Payment applied;
        try {
            applied = transactions.recordLookup(orderId, observed);
        } catch (RuntimeException exception) {
            logApplyFailure(orderId, observed.status(), exception);
            throw exception;
        }
        logApplyCommitted(orderId, observed.status(), applied.status());
        return new Result(applied, true);
    }

    private void logLookupMatchCount(Payment payment, int matchCount) {
        var entry = matchCount > 1 ? log.atWarn() : log.atInfo();
        entry.addKeyValue("step", "pg_lookup").addKeyValue("matchCount", matchCount)
                .addKeyValue("providerOrderId", payment.providerOrderId()).addKeyValue("localStatusBefore", payment.status())
                .log("step=pg_lookup orderId={} providerOrderId={} matchCount={} localStatusBefore={}",
                        payment.orderId(), payment.providerOrderId(), matchCount, payment.status());
    }

    private void logMatchedPayment(Payment payment, PaymentStatus pgStatus) {
        log.atInfo().addKeyValue("step", "pg_lookup").addKeyValue("matchCount", 1)
                .addKeyValue("providerOrderId", payment.providerOrderId()).addKeyValue("pgStatus", pgStatus)
                .addKeyValue("localStatusBefore", payment.status())
                .log("step=pg_lookup orderId={} providerOrderId={} pgStatus={} localStatusBefore={} matchCount=1",
                        payment.orderId(), payment.providerOrderId(), pgStatus, payment.status());
    }

    private void logApplyFailure(long orderId, PaymentStatus pgStatus, RuntimeException exception) {
        String errorType = exception.getClass().getSimpleName();
        log.atError().addKeyValue("step", "payment_apply").addKeyValue("outcome", "failed")
                .addKeyValue("pgStatus", pgStatus).addKeyValue("errorType", errorType)
                .log("step=payment_apply orderId={} pgStatus={} outcome=failed errorType={}", orderId, pgStatus, errorType);
    }

    private void logApplyCommitted(long orderId, PaymentStatus pgStatus, PaymentStatus localStatus) {
        log.atInfo().addKeyValue("step", "payment_apply").addKeyValue("outcome", "committed")
                .addKeyValue("pgStatus", pgStatus).addKeyValue("localStatusAfter", localStatus)
                .log("step=payment_apply orderId={} pgStatus={} outcome=committed localStatusAfter={}", orderId, pgStatus, localStatus);
    }

    public record Result(Payment payment, boolean lookupAvailable) {}
}
