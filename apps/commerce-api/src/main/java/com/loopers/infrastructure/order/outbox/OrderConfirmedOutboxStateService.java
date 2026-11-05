package com.loopers.infrastructure.order.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxStateService {

    private static final String EXHAUSTED_ATTEMPTS = "이전 실행의 결과를 확인하지 못한 시도가 3회 누적됐습니다.";

    private final OrderConfirmedOutboxJpaRepository repository;

    @Transactional(readOnly = true)
    public List<String> findDueEventIds(ZonedDateTime now, int limit) {
        return repository.findAllByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                OrderConfirmedOutboxStatus.PENDING, now, PageRequest.of(0, limit))
            .stream()
            .map(OrderConfirmedOutboxJpaEntity::getEventId)
            .toList();
    }

    @Transactional
    public BeginAttemptResult beginAttempt(String eventId, ZonedDateTime now, Duration maxWait, int maxAttempts) {
        Optional<OrderConfirmedOutboxJpaEntity> found = repository.findByEventId(eventId);
        if (found.isEmpty()) {
            return BeginAttemptResult.skipped();
        }
        OrderConfirmedOutboxJpaEntity row = found.get();
        if (!row.isDue(now)) {
            return BeginAttemptResult.skipped();
        }
        if (row.getCycleAttemptCount() >= maxAttempts) {
            row.markBlocked(now, EXHAUSTED_ATTEMPTS);
            repository.saveAndFlush(row);
            return BeginAttemptResult.blocked(row.getEventId(), row.getOrderId(), "ATTEMPTS_EXHAUSTED");
        }
        row.beginAttempt(now, maxWait, maxAttempts);
        repository.saveAndFlush(row);
        return BeginAttemptResult.started(new OrderConfirmedOutboxAttempt(
            row.getEventId(), row.getOrderId(), row.getMessageKey(), row.getPayload(), row.getCycleAttemptCount()));
    }

    @Transactional
    public boolean markPublished(OrderConfirmedOutboxAttempt attempt, ZonedDateTime publishedAt) {
        Optional<OrderConfirmedOutboxJpaEntity> found = repository.findByEventId(attempt.eventId());
        if (found.isEmpty() || !matchesCurrentAttempt(found.get(), attempt)) {
            return false;
        }
        OrderConfirmedOutboxJpaEntity row = found.get();
        row.markPublished(publishedAt);
        repository.saveAndFlush(row);
        return true;
    }

    @Transactional
    public FailureResult markRetryableFailure(OrderConfirmedOutboxAttempt attempt, ZonedDateTime failedAt,
                                               Duration retryInterval, int maxAttempts, String error) {
        Optional<OrderConfirmedOutboxJpaEntity> found = repository.findByEventId(attempt.eventId());
        if (found.isEmpty() || !matchesCurrentAttempt(found.get(), attempt)) {
            return FailureResult.stale();
        }
        OrderConfirmedOutboxJpaEntity row = found.get();
        boolean blocked = row.scheduleRetryOrBlock(failedAt, retryInterval, maxAttempts, error);
        repository.saveAndFlush(row);
        return blocked
            ? FailureResult.blocked(row.getEventId(), row.getOrderId(), "ATTEMPTS_EXHAUSTED")
            : FailureResult.pending();
    }

    @Transactional
    public FailureResult markBlocked(OrderConfirmedOutboxAttempt attempt, ZonedDateTime blockedAt, String error,
                                     String reason) {
        Optional<OrderConfirmedOutboxJpaEntity> found = repository.findByEventId(attempt.eventId());
        if (found.isEmpty() || !matchesCurrentAttempt(found.get(), attempt)) {
            return FailureResult.stale();
        }
        OrderConfirmedOutboxJpaEntity row = found.get();
        row.markBlocked(blockedAt, error);
        repository.saveAndFlush(row);
        return FailureResult.blocked(row.getEventId(), row.getOrderId(), reason);
    }

    @Transactional
    public ResumeResult resumeBlocked(String eventId, ZonedDateTime resumedAt) {
        OrderConfirmedOutboxJpaEntity row = repository.findByEventId(eventId)
            .orElseThrow(() -> new IllegalStateException("재개할 Outbox를 찾을 수 없습니다. eventId=" + eventId));
        row.resumeBlocked(resumedAt);
        repository.saveAndFlush(row);
        return new ResumeResult(row.getEventId(), row.getOrderId(), row.getAttemptCount(), row.getCycleAttemptCount());
    }

    private boolean matchesCurrentAttempt(OrderConfirmedOutboxJpaEntity row, OrderConfirmedOutboxAttempt attempt) {
        return row.getStatus() == OrderConfirmedOutboxStatus.PENDING
            && row.getCycleAttemptCount() == attempt.attemptNumber();
    }

    public record BeginAttemptResult(BeginAttemptKind kind, OrderConfirmedOutboxAttempt attempt,
                                     String eventId, Long orderId, String reason) {
        static BeginAttemptResult skipped() {
            return new BeginAttemptResult(BeginAttemptKind.SKIPPED, null, null, null, null);
        }

        static BeginAttemptResult started(OrderConfirmedOutboxAttempt attempt) {
            return new BeginAttemptResult(BeginAttemptKind.STARTED, attempt, null, null, null);
        }

        static BeginAttemptResult blocked(String eventId, Long orderId, String reason) {
            return new BeginAttemptResult(BeginAttemptKind.BLOCKED, null, eventId, orderId, reason);
        }
    }

    public record FailureResult(FailureKind kind, String eventId, Long orderId, String reason) {
        static FailureResult pending() {
            return new FailureResult(FailureKind.PENDING, null, null, null);
        }

        static FailureResult stale() {
            return new FailureResult(FailureKind.STALE, null, null, null);
        }

        static FailureResult blocked(String eventId, Long orderId, String reason) {
            return new FailureResult(FailureKind.BLOCKED, eventId, orderId, reason);
        }
    }

    public record ResumeResult(String eventId, Long orderId, int attemptCount, int cycleAttemptCount) {}

    public enum BeginAttemptKind { STARTED, BLOCKED, SKIPPED }
    public enum FailureKind { PENDING, BLOCKED, STALE }
}
