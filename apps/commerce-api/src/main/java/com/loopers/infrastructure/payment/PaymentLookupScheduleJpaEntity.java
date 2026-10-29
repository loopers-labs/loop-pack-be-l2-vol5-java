package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentLookupScheduleRepository.Schedule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "payment_lookup_schedules")
public class PaymentLookupScheduleJpaEntity {
    @Id
    private long orderId;
    @Column(nullable = false)
    private Instant nextLookupAt;
    @Column(nullable = false)
    private int actualAttempts;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected PaymentLookupScheduleJpaEntity() {
    }

    PaymentLookupScheduleJpaEntity(Schedule schedule) {
        orderId = schedule.orderId();
        nextLookupAt = schedule.nextLookupAt();
        actualAttempts = schedule.actualAttempts();
        createdAt = schedule.createdAt();
        updatedAt = schedule.updatedAt();
    }

    Schedule toSchedule() {
        return new Schedule(orderId, nextLookupAt, actualAttempts, createdAt, updatedAt);
    }
}
