package com.loopers.application.order;

import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentLookupNotPermittedException;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Records the adapter's observation before the separate transaction that applies the result. */
@Slf4j
record PaymentGatewayCall(Source source, String operation, long orderId, UUID attemptKey,
                          String providerOrderId, String lookupKey) {
    enum Source { CONFIRM, REFRESH, RECONCILE, CALLBACK }

    static PaymentGatewayResult create(PaymentGateway gateway, OrderPaymentStockService.PreparedPayment prepared,
                                       String cardType, String cardNo) {
        var call = new PaymentGatewayCall(Source.CONFIRM, "CREATE", prepared.orderId(), prepared.attemptKey(),
            prepared.providerOrderId(), null);
        var request = new PaymentCreateRequest(prepared.providerOrderId(), prepared.amount(), cardType, cardNo);
        return call.observe(() -> gateway.create(request));
    }

    static PaymentGatewayResult lookup(PaymentGateway gateway, Source source, long orderId, UUID attemptKey,
                                       String providerOrderId, String transactionKey) {
        if (transactionKey == null) {
            var call = new PaymentGatewayCall(source, "LOOKUP_BY_ORDER", orderId, attemptKey, providerOrderId, null);
            return call.observe(() -> gateway.findByProviderOrderId(providerOrderId));
        }
        return lookupByKey(gateway, source, orderId, attemptKey, providerOrderId, transactionKey);
    }

    static PaymentGatewayResult lookupByKey(PaymentGateway gateway, Source source, long orderId, UUID attemptKey,
                                            String providerOrderId, String transactionKey) {
        var call = new PaymentGatewayCall(source, "LOOKUP_BY_KEY", orderId, attemptKey, providerOrderId, transactionKey);
        return call.observe(() -> gateway.findByTransactionKey(transactionKey));
    }

    private PaymentGatewayResult observe(Supplier<PaymentGatewayResult> invoke) {
        long start = System.nanoTime();
        PaymentGatewayResult result;
        try {
            result = invoke.get();
        } catch (PaymentLookupNotPermittedException denied) {
            recordResult(start, lookupKey, "NOT_SENT", "CIRCUIT_OPEN", "NONE");
            throw denied;
        } catch (RuntimeException failure) {
            recordResult(start, lookupKey, "ERROR", "ADAPTER_EXCEPTION", "NONE");
            throw failure;
        }
        String key = lookupKey;
        String status = "NONE";
        if (result.kind() == PaymentGatewayResult.Kind.OBSERVED && result.transactions().size() == 1) {
            var observed = result.transactions().getFirst();
            key = observed.transactionKey();
            status = observed.status().name();
        }
        recordResult(start, key, result.kind().name(), result.failure().name(), status);
        return result;
    }

    private void recordResult(long start, String key, String result, String reason, String pgStatus) {
        log.info("payment_gateway_result source={} operation={} orderId={} attemptKey={} providerOrderId={} "
                + "transactionKey={} result={} reason={} pgStatus={} elapsedMs={}",
            source, operation, orderId, attemptKey, PaymentLogId.safe(providerOrderId), PaymentLogId.safe(key),
            result, reason, pgStatus, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
}
