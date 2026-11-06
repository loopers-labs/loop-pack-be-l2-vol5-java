package com.loopers.interfaces.scheduler;

import com.loopers.application.ordering.service.RecoverOrderOutboxService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("outbox-recover")
@RequiredArgsConstructor
public class RecoverOrderOutboxCommand implements CommandLineRunner {
    private final RecoverOrderOutboxService recovery;

    @Value("${order-events.recovery.order-id}")
    private long orderId;

    @Value("${order-events.recovery.event-id}")
    private UUID eventId;

    @Override
    public void run(String... args) {
        var result = recovery.execute(orderId, eventId);
        log.info("Outbox recovery orderId={} eventId={} result={}", orderId, eventId, result);
    }
}
