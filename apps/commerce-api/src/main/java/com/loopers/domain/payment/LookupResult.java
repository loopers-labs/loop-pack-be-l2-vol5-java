package com.loopers.domain.payment;

import java.util.List;

public sealed interface LookupResult {

    record Found(List<PgTransaction> transactions) implements LookupResult, LookupObservation {

        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    record NotFound() implements LookupResult, LookupObservation {
    }

    record Unavailable() implements LookupResult {
    }
}
