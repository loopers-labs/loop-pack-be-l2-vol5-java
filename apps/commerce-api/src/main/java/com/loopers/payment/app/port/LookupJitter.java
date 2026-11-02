package com.loopers.payment.app.port;

@FunctionalInterface
public interface LookupJitter {
    long chooseMillis(long maximumMillis);
}
