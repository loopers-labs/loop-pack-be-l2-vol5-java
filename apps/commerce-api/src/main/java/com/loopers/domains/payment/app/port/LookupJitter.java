package com.loopers.domains.payment.app.port;

@FunctionalInterface
public interface LookupJitter {
    long chooseMillis(long maximumMillis);
}
