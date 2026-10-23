package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReceivePaymentCallbackFacade {
    private final PaymentRepository payments;
    private final OrderPaymentTransactions transactions;
    private final PaymentGateway gateway;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReconcileOrderPaymentFacade.Result receive(Command notification) {
        var storedPayment = payments.findByProviderOrderId(notification.providerOrderId())
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        if (storedPayment.transactionKey() != null
                && !storedPayment.transactionKey().equals(notification.transactionKey())) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        PaymentGateway.ObservedPayment observed;
        try {
            // 콜백의 상태는 알림이다. 고정 PG의 거래 조회 결과만 주문에 반영한다.
            observed = gateway.findByTransactionKey(notification.transactionKey());
        } catch (PaymentGateway.LookupBlockedException | PaymentGateway.UnconfirmedException exception) {
            var currentPayment = payments.findByProviderOrderId(notification.providerOrderId()).orElseThrow();
            return new ReconcileOrderPaymentFacade.Result(currentPayment, false);
        }
        return new ReconcileOrderPaymentFacade.Result(
                transactions.recordLookup(storedPayment.orderId(), observed), true);
    }

    public record Command(String providerOrderId, String transactionKey, PaymentStatus status) {
        public Command {
            if (providerOrderId == null || providerOrderId.length() < 6
                    || transactionKey == null || transactionKey.isBlank()
                    || status == null || status == PaymentStatus.UNKNOWN) {
                throw new CoreException(ErrorType.INVALID_REQUEST);
            }
        }
    }
}
