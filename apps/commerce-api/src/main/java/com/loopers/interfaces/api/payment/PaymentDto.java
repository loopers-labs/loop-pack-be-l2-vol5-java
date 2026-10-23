package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.ReceivePaymentCallbackFacade;
import com.loopers.application.payment.RequestOrderPaymentFacade;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentStatus;

import java.util.UUID;

public class PaymentDto {
    public record ConfirmRequest(UUID paymentAttemptKey, String cardType, String cardNo) {
        public RequestOrderPaymentFacade.Command toCommand() {
            return new RequestOrderPaymentFacade.Command(paymentAttemptKey, cardType, cardNo);
        }

        @Override
        public String toString() {
            return "ConfirmRequest[paymentAttemptKey=" + paymentAttemptKey + ", cardType=" + cardType + "]";
        }
    }

    public record CallbackRequest(String providerOrderId, String transactionKey, PaymentStatus status) {
        public ReceivePaymentCallbackFacade.Command toCommand() {
            return new ReceivePaymentCallbackFacade.Command(providerOrderId, transactionKey, status);
        }
    }

    public record Result(long orderId, OrderStatus orderStatus, PaymentStatus paymentStatus,
            String providerOrderId, String transactionKey, String orderLookupPath, Boolean lookupAvailable) {
        public static Result from(RequestOrderPaymentFacade.Result payment, Boolean lookupAvailable) {
            return new Result(payment.orderId(), payment.orderStatus(), payment.paymentStatus(),
                    payment.providerOrderId(), payment.transactionKey(),
                    "/api/v1/orders/" + payment.orderId(), lookupAvailable);
        }
    }
}
