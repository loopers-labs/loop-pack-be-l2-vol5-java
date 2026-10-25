package com.loopers.testpg.application;

public record OrderStatsInfo(int createRequests, int lookupRequests, int callbackAttempts, int transactions) {}
