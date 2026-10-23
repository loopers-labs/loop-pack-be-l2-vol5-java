package com.loopers.application.order;

import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentLookupNotPermittedException;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentProviderUnavailableException;
import com.loopers.domain.payment.PaymentRecoveryException;
import com.loopers.domain.payment.PaymentResultConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One bounded external lookup per invocation, outside all DB transactions; never creates or retries a payment. */
@Slf4j
@Service
public class OrderPaymentRecoveryService {
    private static final String PROVIDER_ACCOUNT = "135135";
    private final OrderPaymentStockService stocks;
    private final OrderPaymentQueryService queries;
    private final ObjectProvider<PaymentGateway> gateways;

    public OrderPaymentRecoveryService(OrderPaymentStockService stocks, OrderPaymentQueryService queries,
                                       ObjectProvider<PaymentGateway> gateways) {
        this.stocks = stocks;
        this.queries = queries;
        this.gateways = gateways;
    }

    public OrderPaymentStockService.AppliedPayment reconcile(String requester, long orderId) {
        requireOutsideTransaction();
        var stored = stocks.inspectCustomerRecovery(requester, orderId);
        if (stored.terminal()) {
            return stored.result();
        }
        if (!PROVIDER_ACCOUNT.equals(stored.providerAccount())) {
            throw conflict(stored, "RECONCILE", "PROVIDER_ACCOUNT_MISMATCH");
        }
        var gateway = gateway();
        PaymentGatewayResult observation;
        try {
            requireOutsideTransaction();
            observation = stored.transactionKey() == null
                ? gateway.findByProviderOrderId(stored.providerOrderId())
                : gateway.findByTransactionKey(stored.transactionKey());
        } catch (PaymentLookupNotPermittedException denied) {
            // No external observation occurred. A concurrent completion may already have committed.
            return stocks.inspectRecovery(stored.orderId(), stored.attemptKey()).result();
        }
        apply(stored, observation, "RECONCILE");
        return stocks.inspectRecovery(stored.orderId(), stored.attemptKey()).result();
    }

    /** Notification status is deliberately not accepted: only the authoritative GET may drive TX B. */
    public void callback(String providerOrderId, String transactionKey) {
        requireOutsideTransaction();
        var target = queries.callbackTarget(PROVIDER_ACCOUNT, providerOrderId);
        var stored = stocks.inspectRecovery(target.orderId(), target.attemptKey());
        if (!PROVIDER_ACCOUNT.equals(stored.providerAccount()) || !providerOrderId.equals(stored.providerOrderId())
            || (stored.transactionKey() != null && !transactionKey.equals(stored.transactionKey()))) {
            throw conflict(stored, "CALLBACK", "NOTIFICATION_IDENTITY_MISMATCH");
        }
        var gateway = gateway();
        PaymentGatewayResult observation;
        try {
            requireOutsideTransaction();
            observation = gateway.findByTransactionKey(transactionKey);
        } catch (PaymentLookupNotPermittedException denied) {
            // Acknowledging unverified notification would hide a recoverable failure. State is unchanged.
            throw resultUnavailable();
        }
        // Defend the requested transaction identity even if a future adapter violates its current contract.
        if (observation.kind() == PaymentGatewayResult.Kind.OBSERVED && observation.transactions().size() == 1
            && !transactionKey.equals(observation.transactions().getFirst().transactionKey())) {
            throw conflict(stored, "CALLBACK", "LOOKUP_TRANSACTION_MISMATCH");
        }
        apply(stored, observation, "CALLBACK");
        if (observation.kind() != PaymentGatewayResult.Kind.OBSERVED) {
            throw resultUnavailable();
        }
    }

    private void apply(OrderPaymentStockService.RecoveryPayment stored, PaymentGatewayResult observation, String source) {
        switch (observation.kind()) {
            case OBSERVED -> {
                if (observation.transactions().size() != 1) {
                    throw conflict(stored, source, "MULTIPLE_TRANSACTIONS");
                }
                var result = stocks.apply(stored.orderId(), stored.attemptKey(), observation.transactions().getFirst());
                if (result.observationResult() == PaymentObservationResult.CONFLICT) {
                    throw new PaymentResultConflictException();
                }
            }
            case CONFLICT -> throw conflict(stored, source, "LOOKUP_IDENTITY_CONFLICT");
            // A lookup 404/empty/422 is not the strict no-effect CREATE refusal and never returns stock.
            case UNKNOWN, NOT_FOUND, REJECTED_NO_EFFECT -> stocks.markUnknown(stored.orderId(), stored.attemptKey());
        }
    }

    private PaymentGateway gateway() {
        PaymentGateway gateway = gateways.getIfAvailable();
        if (gateway == null) {
            throw new PaymentProviderUnavailableException();
        }
        return gateway;
    }

    private PaymentResultConflictException conflict(OrderPaymentStockService.RecoveryPayment stored,
                                                    String source, String reason) {
        log.warn("payment_result_conflict source={} orderId={} attemptKey={} reason={}",
            source, stored.orderId(), stored.attemptKey(), reason);
        return new PaymentResultConflictException();
    }

    private PaymentRecoveryException resultUnavailable() {
        return new PaymentRecoveryException(PaymentRecoveryException.Reason.PAYMENT_RESULT_UNAVAILABLE);
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Payment recovery must start outside a database transaction");
        }
    }
}
