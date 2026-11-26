package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.PaymentCancelCommand;
import com.loopers.domains.payment.app.port.PaymentCancelResult;
import com.loopers.domains.payment.app.port.PaymentCreateCommand;
import com.loopers.domains.payment.app.port.PaymentCreateResult;
import com.loopers.domains.payment.app.port.PaymentGateway;
import com.loopers.domains.payment.app.port.PaymentTransactionsLookup;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public class CircuitBreakingPaymentGateway implements PaymentGateway {
    private final PaymentGateway paymentGateway;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakingPaymentGateway(PaymentGateway paymentGateway, CircuitBreaker circuitBreaker) {
        this.paymentGateway = paymentGateway;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String providerId() {
        return paymentGateway.providerId();
    }

    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        return paymentGateway.create(command);
    }

    @Override
    public PaymentTransactionsLookup verifyOrder(String providerOrderId, String storedTransactionKey) {
        try {
            return circuitBreaker.executeSupplier(() -> paymentGateway.verifyOrder(providerOrderId, storedTransactionKey));
        } catch (CallNotPermittedException e) {
            return new PaymentTransactionsLookup.NotCalled();
        }
    }

    // 취소는 조회 회로를 거치지 않는다. 회로가 열려도 환불은 나가야 하고, 취소 실패가 조회 회로 표본을 흐리면 안 된다.
    @Override
    public PaymentCancelResult cancel(PaymentCancelCommand command) {
        return paymentGateway.cancel(command);
    }
}
