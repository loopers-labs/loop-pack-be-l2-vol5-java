package com.loopers.application.payment.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentLookupScheduleRepository {
    record Schedule(long orderId, Instant nextLookupAt, int actualAttempts, Instant createdAt, Instant updatedAt) {
    }

    void save(Schedule schedule);

    Optional<Schedule> findByOrderId(long orderId);

    List<Long> findDue(Instant now, int limit);

    void delete(long orderId);
}
