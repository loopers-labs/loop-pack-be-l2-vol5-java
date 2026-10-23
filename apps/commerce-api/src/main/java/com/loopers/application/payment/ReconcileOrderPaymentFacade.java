package com.loopers.application.payment;

import com.loopers.domain.payment.Payment;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReconcileOrderPaymentFacade {
    private final OrderPaymentTransactions transactions;
    private final PaymentGateway gateway;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result reconcile(Long userId, long orderId) {
        Payment paymentBeforeLookup = transactions.findForReconciliation(userId, orderId);
        PaymentGateway.ObservedPayment observed;
        try {
            var existingTransactions = gateway.findByOrderId(paymentBeforeLookup.providerOrderId());
            if (existingTransactions.size() != 1) {
                // 조회 0건도 아직 도착하지 않은 POST의 부재를 증명하지 못한다.
                return new Result(transactions.recordUnknown(orderId), true);
            }
            observed = existingTransactions.getFirst();
        } catch (PaymentGateway.LookupBlockedException | PaymentGateway.UnconfirmedException exception) {
            // 조회 장애가 결제 결과를 바꾸지는 않는다. 동시 반영된 결과까지 포함해 현재 저장값을 다시 읽는다.
            return new Result(transactions.findForReconciliation(userId, orderId), false);
        }
        return new Result(transactions.recordLookup(orderId, observed), true);
    }

    public record Result(Payment payment, boolean lookupAvailable) {}
}
