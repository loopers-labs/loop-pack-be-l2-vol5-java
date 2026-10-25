package com.loopers.domain.payment;

import java.util.List;

public sealed interface LookupResult {

    sealed interface Observation permits Found, NotFound {
    }

    record Found(List<PgTransaction> transactions) implements LookupResult, Observation {

        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    record NotFound() implements LookupResult, Observation {
    }

    record Unavailable() implements LookupResult {
    }
}
