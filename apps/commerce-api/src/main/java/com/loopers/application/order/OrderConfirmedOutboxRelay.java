package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxRelay {

    static final String TOPIC = "order-events";
    static final int BATCH_SIZE = 50;

    private final OrderConfirmedOutboxRepository outboxRepository;
    private final OutboxPublisher outboxPublisher;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:5000}")
    public void relay() {
        List<OrderConfirmedOutbox> pending = outboxRepository.findPendingToProcess(ZonedDateTime.now(), BATCH_SIZE);

        if (pending.isEmpty()) {
            return;
        }

        log.info("Outbox relay 시작: PENDING={}", pending.size());

        for (OrderConfirmedOutbox outbox : pending) {
            process(outbox);
        }
    }

    void process(OrderConfirmedOutbox outbox) {
        try {
            outboxPublisher.publish(TOPIC, outbox.getMessageKey(), outbox.getPayload());
            markPublished(outbox.getId(), ZonedDateTime.now());
            log.info("Outbox 발행 완료: eventId={}", outbox.getEventId());
        } catch (Exception e) {
            ZonedDateTime nextAttempt = ZonedDateTime.now().plusSeconds(retryDelaySeconds(outbox.getAttemptCount()));
            recordFailure(outbox.getId(), e.getMessage(), nextAttempt);
            log.warn("Outbox 발행 실패: eventId={}, attempt={}, error={}",
                outbox.getEventId(), outbox.getAttemptCount() + 1, e.getMessage());
        }
    }

    @Transactional
    public void markPublished(Long outboxId, ZonedDateTime publishedAt) {
        outboxRepository.findById(outboxId).ifPresent(o -> {
            o.markPublished(publishedAt);
            outboxRepository.save(o);
        });
    }

    @Transactional
    public void recordFailure(Long outboxId, String error, ZonedDateTime nextAttemptAt) {
        outboxRepository.findById(outboxId).ifPresent(o -> {
            o.recordFailure(error, nextAttemptAt);
            outboxRepository.save(o);
        });
    }

    private long retryDelaySeconds(int attemptCount) {
        return Math.min(60L * (1L << Math.min(attemptCount, 5)), 3600L);
    }
}
