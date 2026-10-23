package com.loopers.batch.payment;

import org.springframework.stereotype.Component;

@Component
public class PaymentStateInterpreter {
    public RemotePaymentState interpret(PaymentLookupClient.LookupResult result) {
        if (!result.isFound()) {
            return RemotePaymentState.UNKNOWN;
        }
        return switch (result.paymentStatus()) {
            case "DONE" -> RemotePaymentState.SUCCESS;
            case "ABORTED", "EXPIRED" -> RemotePaymentState.FAILED;
            case "CANCELED" -> RemotePaymentState.CANCELED;
            case "PARTIAL_CANCELED" -> RemotePaymentState.PARTIALLY_CANCELED;
            case "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> RemotePaymentState.PROCESSING;
            default -> RemotePaymentState.UNKNOWN;
        };
    }
}
