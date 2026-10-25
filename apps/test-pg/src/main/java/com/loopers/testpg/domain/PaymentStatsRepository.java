package com.loopers.testpg.domain;

public interface PaymentStatsRepository {
    void recordCreateRequest(String orderId);

    void recordLookupRequest(String orderId);

    void recordCallbackAttempt(String orderId);

    PaymentStats find(String orderId);
}
