package com.loopers.testpg.infrastructure;

import com.loopers.testpg.domain.PaymentStats;
import com.loopers.testpg.domain.PaymentStatsRepository;
import org.springframework.stereotype.Repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
public class InMemoryPaymentStatsRepository implements PaymentStatsRepository {
    private final ConcurrentHashMap<String, AtomicInteger> createRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> lookupRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> callbackAttempts = new ConcurrentHashMap<>();

    @Override
    public void recordCreateRequest(String orderId) {
        increase(createRequests, orderId);
    }

    @Override
    public void recordLookupRequest(String orderId) {
        increase(lookupRequests, orderId);
    }

    @Override
    public void recordCallbackAttempt(String orderId) {
        increase(callbackAttempts, orderId);
    }

    @Override
    public PaymentStats find(String orderId) {
        return new PaymentStats(count(createRequests, orderId), count(lookupRequests, orderId),
            count(callbackAttempts, orderId));
    }

    private static void increase(ConcurrentHashMap<String, AtomicInteger> counts, String orderId) {
        counts.computeIfAbsent(orderId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static int count(ConcurrentHashMap<String, AtomicInteger> counts, String orderId) {
        AtomicInteger count = counts.get(orderId);
        return count == null ? 0 : count.get();
    }
}
