package com.loopers.domain.payment;

public sealed interface LookupObservation permits LookupResult.Found, LookupResult.NotFound {
}
