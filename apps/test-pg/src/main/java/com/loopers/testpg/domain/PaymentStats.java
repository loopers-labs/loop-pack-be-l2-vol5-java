package com.loopers.testpg.domain;

public record PaymentStats(int createRequests, int lookupRequests, int callbackAttempts) {}
