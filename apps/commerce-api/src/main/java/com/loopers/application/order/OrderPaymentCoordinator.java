package com.loopers.application.order;

import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentLookupNotPermittedException;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentResultConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * 신규/동일 요청 조율과 고객 최초 요청의 제한 조회. callback/reconcile HTTP는 별도 후속 범위다.
 * 별도 프록시의 TX A/B를 호출하며, 외부 TX를 suspend한 채 그 잠금을 보유하지 않도록 진입부터 거절한다.
 */
@Service
@ConditionalOnProperty(prefix = "commerce.test-pg", name = "enabled", havingValue = "true")
public class OrderPaymentCoordinator {
    private static final String PROVIDER_ACCOUNT = "135135";
    private final OrderPaymentStockService stocks;
    private final PaymentGateway gateway;
    private final PaymentConfirmationWaiter waiter;

    public OrderPaymentCoordinator(OrderPaymentStockService stocks, PaymentGateway gateway) {
        this(stocks, gateway, null);
    }

    @Autowired
    public OrderPaymentCoordinator(OrderPaymentStockService stocks, PaymentGateway gateway, PaymentConfirmationWaiter waiter) {
        this.stocks = stocks;
        this.gateway = gateway;
        this.waiter = waiter;
    }

    /** 신규 DRAFT 전용. 반복 호출 거절을 최종 고객 confirm의 멱등 응답으로 사용하지 않는다. */
    public OrderPaymentStockService.AppliedPayment startNew(String requester, long orderId, UUID attemptKey,
                                                            String providerOrderId, String cardType, String cardNo) {
        requireOutsideTransaction();
        var prepared = stocks.prepareNew(requester, orderId, attemptKey, PROVIDER_ACCOUNT, providerOrderId, cardType, cardNo);
        return dispatch(prepared, cardType, cardNo);
    }

    /** 동일 요청은 현재 저장 상태만 반환한다. HTTP 생성·조회·긴 대기를 중복 시작하지 않는다. */
    public OrderPaymentStockService.AppliedPayment startOrReuse(String requester, long orderId, UUID attemptKey,
                                                                String providerOrderId, String cardType, String cardNo) {
        requireOutsideTransaction();
        var prepared = stocks.prepareOrReuse(requester, orderId, attemptKey, PROVIDER_ACCOUNT, providerOrderId, cardType, cardNo);
        return prepared.dispatch() == null ? prepared.result() : dispatch(prepared.dispatch(), cardType, cardNo);
    }

    /** Only the execution granted first dispatch waits. A duplicate returns committed state without PG calls. */
    public OrderPaymentStockService.AppliedPayment startAndAwait(String requester, long orderId, UUID attemptKey,
                                                                String cardType, String cardNo,
                                                                PaymentConfirmationDeadline deadline) {
        requireOutsideTransaction();
        if (waiter == null) {
            throw new IllegalStateException("Customer confirmation requires a bounded waiter");
        }
        var prepared = stocks.prepareCustomer(requester, orderId, attemptKey, PROVIDER_ACCOUNT, cardType, cardNo);
        if (prepared.dispatch() == null) {
            return prepared.result();
        }
        // TX A is committed, including the dispatch claim. An exhausted budget must never trigger a later re-send.
        var result = deadline.permitsCreate() && !Thread.currentThread().isInterrupted()
            ? dispatch(prepared.dispatch(), cardType, cardNo) : stocks.markUnknown(orderId, attemptKey);
        requireUnconflicted(result);
        if (terminal(result)) {
            return result;
        }
        return waiter.await(deadline, () -> stocks.inspectPrepared(requester, orderId, attemptKey).result(),
            this::terminal, () -> {
                var lookup = refreshForWait(requester, orderId, attemptKey, deadline);
                requireUnconflicted(lookup.result());
                return lookup;
            });
    }

    private boolean terminal(OrderPaymentStockService.AppliedPayment result) {
        return result.paymentStatus() == PaymentAttemptStatus.SUCCESS || result.paymentStatus() == PaymentAttemptStatus.FAILED;
    }

    private void requireUnconflicted(OrderPaymentStockService.AppliedPayment result) {
        if (result.observationResult() == PaymentObservationResult.CONFLICT) {
            throw new PaymentResultConflictException();
        }
    }

    private PaymentConfirmationWaiter.LookupAttempt<OrderPaymentStockService.AppliedPayment> refreshForWait(
        String requester, long orderId, UUID attemptKey, PaymentConfirmationDeadline deadline) {
        requireOutsideTransaction();
        var stored = stocks.inspectPrepared(requester, orderId, attemptKey);
        if (stored.terminal() || !deadline.permitsLookup() || Thread.currentThread().isInterrupted()) {
            return new PaymentConfirmationWaiter.LookupAttempt<>(stored.result(), false);
        }
        if (!PROVIDER_ACCOUNT.equals(stored.providerAccount())) {
            return new PaymentConfirmationWaiter.LookupAttempt<>(stocks.preserveConflict(orderId, attemptKey), false);
        }
        PaymentGatewayResult observation;
        try {
            requireOutsideTransaction();
            observation = stored.transactionKey() == null
                ? gateway.findByProviderOrderId(stored.providerOrderId())
                : gateway.findByTransactionKey(stored.transactionKey());
        } catch (PaymentLookupNotPermittedException denied) {
            return new PaymentConfirmationWaiter.LookupAttempt<>(
                stocks.inspectPrepared(requester, orderId, attemptKey).result(), false);
        }
        return new PaymentConfirmationWaiter.LookupAttempt<>(applyObservation(orderId, attemptKey, observation), true);
    }

    private OrderPaymentStockService.AppliedPayment dispatch(OrderPaymentStockService.PreparedPayment prepared,
                                                             String cardType, String cardNo) {
        // 외부 TX가 없는 호출에서 별도 Spring 프록시가 반환했으므로 TX A 커밋이 완료됐다.
        requireOutsideTransaction();
        var observation = gateway.create(new PaymentCreateRequest(prepared.providerOrderId(), prepared.amount(),
            cardType, cardNo));
        if (observation.kind() == PaymentGatewayResult.Kind.REJECTED_NO_EFFECT) {
            return stocks.rejectBeforeCreate(prepared.orderId(), prepared.attemptKey(), prepared.providerAccount(),
                prepared.providerOrderId(), prepared.amount());
        }
        return applyObservation(prepared.orderId(), prepared.attemptKey(), observation);
    }

    /** 저장된 식별자로 한 번만 조회한다. 고객 reconcile의 HTTP·예산 정책을 정의하는 메서드가 아니다. */
    public OrderPaymentStockService.AppliedPayment refreshOnce(String requester, long orderId, UUID attemptKey) {
        requireOutsideTransaction();
        var stored = stocks.inspectPrepared(requester, orderId, attemptKey);
        if (stored.terminal()) {
            return stored.result();
        }
        if (!PROVIDER_ACCOUNT.equals(stored.providerAccount())) {
            return stocks.preserveConflict(orderId, attemptKey);
        }
        requireOutsideTransaction();
        PaymentGatewayResult observation;
        try {
            observation = stored.transactionKey() == null
                ? gateway.findByProviderOrderId(stored.providerOrderId())
                : gateway.findByTransactionKey(stored.transactionKey());
        } catch (PaymentLookupNotPermittedException denied) {
            return stocks.inspectPrepared(requester, orderId, attemptKey).result();
        }
        // 조회 404/빈 목록/422는 생성 전 효과 0건 거절이 아니다. create 재발송 경로는 없다.
        return applyObservation(orderId, attemptKey, observation);
    }

    private OrderPaymentStockService.AppliedPayment applyObservation(long orderId, UUID attemptKey,
                                                                      PaymentGatewayResult observation) {
        return switch (observation.kind()) {
            case OBSERVED -> observation.transactions().size() == 1
                ? stocks.apply(orderId, attemptKey, observation.transactions().getFirst())
                : stocks.preserveConflict(orderId, attemptKey);
            case CONFLICT -> stocks.preserveConflict(orderId, attemptKey);
            case UNKNOWN, NOT_FOUND, REJECTED_NO_EFFECT -> stocks.markUnknown(orderId, attemptKey);
        };
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Payment HTTP orchestration must start outside a database transaction");
        }
    }
}
