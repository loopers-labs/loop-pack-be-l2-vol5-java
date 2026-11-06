package com.loopers.application.ordering.service;

import com.loopers.domain.ordering.model.OrderRecord;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmationRecordFactory {
    private final Clock clock;
    private final Supplier<UUID> eventIds;

    public OrderConfirmationRecordFactory() {
        this(Clock.systemUTC(), UUID::randomUUID);
    }

    OrderConfirmationRecordFactory(Clock clock, Supplier<UUID> eventIds) {
        this.clock = clock;
        this.eventIds = eventIds;
    }

    public OrderRecord create(long userId, long amount) {
        return OrderRecord.paidByPg(userId, amount, clock.instant().truncatedTo(ChronoUnit.MICROS), eventIds.get());
    }
}
