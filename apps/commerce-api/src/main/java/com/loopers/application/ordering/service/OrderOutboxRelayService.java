package com.loopers.application.ordering.service;

import com.loopers.application.ordering.gateway.OrderEventTransport;
import com.loopers.application.ordering.gateway.OrderEventTransport.Published;
import com.loopers.application.ordering.gateway.OrderOutboxRepository;
import com.loopers.application.ordering.gateway.OrderOutboxRepository.Candidate;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderOutboxRelayService {
    private static final int BATCH_SIZE = 5;
    private static final int MAX_ATTEMPTS = 5;
    private static final int ACK_WAIT_SECONDS = 10;
    // A reservation survives a process stop. Allow the bounded send to finish before reclaiming it.
    private static final int RESERVATION_SECONDS = 20;

    private final OrderOutboxRepository outbox;
    private final OrderEventTransport transport;
    private final ReentrantLock pollLock = new ReentrantLock();

    @Transactional(propagation = Propagation.NEVER)
    public void poll() {
        if (!pollLock.tryLock()) {
            return;
        }
        try {
            Instant now = Instant.now();
            outbox.blockExhausted(now, MAX_ATTEMPTS);
            for (Candidate candidate : outbox.due(now, BATCH_SIZE)) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                relay(candidate);
            }
        } finally {
            pollLock.unlock();
        }
    }

    private void relay(Candidate candidate) {
        Instant started = Instant.now();
        if (!outbox.reserve(candidate, started, started.plusSeconds(RESERVATION_SECONDS))) {
            return;
        }
        Published delivery;
        try {
            delivery = transport.send(candidate.topic(), candidate.key(), candidate.payload()).get(ACK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        } catch (TimeoutException | RuntimeException exception) {
            failed(candidate, exception);
            return;
        } catch (ExecutionException exception) {
            failed(candidate, exception.getCause());
            return;
        }

        if (outbox.publish(candidate, Instant.now(), delivery)) {
            log.info("Outbox published eventId={} orderId={} topic={} partition={} offset={}",
                candidate.eventId(), candidate.key(), delivery.topic(), delivery.partition(), delivery.offset());
        }
    }

    private void failed(Candidate candidate, Throwable failure) {
        int attempt = candidate.attempts() + 1;
        boolean blocked = attempt == MAX_ATTEMPTS;
        Instant now = Instant.now();
        Instant next = blocked ? now : now.plusMillis(retryDelayMillis(attempt));
        String reason = failure instanceof TimeoutException ? "BROKER_ACK_TIMEOUT" : "KAFKA_SEND_FAILED";
        if (outbox.fail(candidate, next, reason, blocked)) {
            log.warn("Outbox send failed eventId={} orderId={} attempt={} blocked={} nextAttemptAt={} reason={} cause={}",
                candidate.eventId(), candidate.key(), attempt, blocked, blocked ? null : next, reason,
                failure.getClass().getSimpleName());
        }
    }

    private long retryDelayMillis(int attempt) {
        long base = 3_000L << (attempt - 1);
        long jitter = base / 5;
        return ThreadLocalRandom.current().nextLong(base - jitter, base + jitter + 1);
    }
}
