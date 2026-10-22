package com.loopers.domain.payment;

public sealed interface CreateObservation {

    record Accepted(TransactionKey transactionKey) implements CreateObservation {
    }

    record RejectedNoEffect() implements CreateObservation {
    }

    record NotSent() implements CreateObservation {
    }

    record CreateUnknown() implements CreateObservation {
    }
}
