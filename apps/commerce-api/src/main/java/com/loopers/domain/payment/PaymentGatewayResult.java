package com.loopers.domain.payment;

import java.util.List;
import java.util.Objects;

/** UNKNOWN is an inability to observe, not a payment FAILED result. */
public record PaymentGatewayResult(Kind kind, List<PaymentTransaction> transactions, Failure failure) {
    public PaymentGatewayResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(failure, "failure");
        transactions = List.copyOf(transactions);
        if ((kind == Kind.UNKNOWN) != (failure != Failure.NONE)
            || ((kind == Kind.OBSERVED || kind == Kind.CONFLICT) != !transactions.isEmpty())) {
            throw new IllegalArgumentException("Invalid PG observation shape");
        }
    }

    public static PaymentGatewayResult observed(List<PaymentTransaction> transactions) {
        return new PaymentGatewayResult(Kind.OBSERVED, transactions, Failure.NONE);
    }

    public static PaymentGatewayResult conflict(List<PaymentTransaction> transactions) {
        return new PaymentGatewayResult(Kind.CONFLICT, transactions, Failure.NONE);
    }

    public static PaymentGatewayResult rejected() {
        return new PaymentGatewayResult(Kind.REJECTED_NO_EFFECT, List.of(), Failure.NONE);
    }

    public static PaymentGatewayResult notFound() {
        return new PaymentGatewayResult(Kind.NOT_FOUND, List.of(), Failure.NONE);
    }

    public static PaymentGatewayResult unknown(Failure failure) {
        return new PaymentGatewayResult(Kind.UNKNOWN, List.of(), failure);
    }

    public enum Kind {
        OBSERVED, REJECTED_NO_EFFECT, NOT_FOUND, UNKNOWN, CONFLICT
    }

    public enum Failure {
        NONE, TIMEOUT, NETWORK, HTTP_STATUS, INVALID_RESPONSE
    }
}
