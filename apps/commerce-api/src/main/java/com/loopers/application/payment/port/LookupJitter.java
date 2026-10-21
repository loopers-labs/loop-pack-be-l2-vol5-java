package com.loopers.application.payment.port;

@FunctionalInterface
public interface LookupJitter {
    long chooseMillis(long maximumMillis);
}
