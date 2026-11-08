package com.loopers.domain.outbox;

/** Compares immutable delivery facts without changing the existing row's delivery progress. */
public interface OutboxMessageMatcher {
    boolean matches(OutboxMessage expected, OutboxMessage actual);
}
