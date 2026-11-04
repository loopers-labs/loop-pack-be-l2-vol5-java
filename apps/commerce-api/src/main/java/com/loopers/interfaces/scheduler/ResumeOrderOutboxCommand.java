package com.loopers.interfaces.scheduler;

import com.loopers.application.ordering.gateway.OrderOutboxRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("outbox-resume")
@RequiredArgsConstructor
public class ResumeOrderOutboxCommand implements CommandLineRunner {
    private final OrderOutboxRepository outbox;

    @Value("${order-events.relay.resume-id}")
    private UUID eventId;

    @Override
    public void run(String... args) {
        if (!outbox.resume(eventId, Instant.now())) {
            throw new IllegalStateException("BLOCKED 상태의 Outbox 이벤트를 찾지 못했습니다. eventId=" + eventId);
        }
        log.info("Outbox resumed eventId={}", eventId);
    }
}
