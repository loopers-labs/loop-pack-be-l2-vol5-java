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

    // 읽은 행의 시도 번호와 기한이 DB에 그대로일 때만 시작한다. 다른 실행이 먼저 시작했으면 건너뛴다.
    @Transactional
    public BeginAttemptResult beginAttempt(String eventId, ZonedDateTime now, Duration nextAttemptDelay, int maxAttempts) {
        Optional<OrderConfirmedOutboxJpaEntity> found = repository.findByEventId(eventId);
        if (found.isEmpty() || !found.get().isDue(now)) {
            return BeginAttemptResult.skipped();
        }
        OrderConfirmedOutboxJpaEntity row = found.get();
        int currentAttempt = row.getCycleAttemptCount();
        if (currentAttempt >= maxAttempts) {
            if (repository.markBlocked(eventId, currentAttempt, EXHAUSTED_ATTEMPTS, now) != 1) {
                return BeginAttemptResult.skipped();
            }
            return BeginAttemptResult.blocked(row.getEventId(), row.getOrderId(), "ATTEMPTS_EXHAUSTED");
        }
        if (repository.beginAttempt(eventId, currentAttempt, now.plus(nextAttemptDelay), now) != 1) {
            return BeginAttemptResult.skipped();
        }
        return BeginAttemptResult.started(new OrderConfirmedOutboxAttempt(
            row.getEventId(), row.getOrderId(), row.getMessageKey(), row.getPayload(), currentAttempt + 1));
    }

    // 결과 저장은 PENDING이고 같은 시도 번호일 때만 적용해 늦은 결과가 다음 시도를 덮지 않게 한다.
    @Transactional
    public boolean markPublished(OrderConfirmedOutboxAttempt attempt, ZonedDateTime publishedAt) {
        return repository.markPublished(attempt.eventId(), attempt.attemptNumber(), publishedAt) == 1;
    }

    @Transactional
    public FailureResult markRetryableFailure(OrderConfirmedOutboxAttempt attempt, ZonedDateTime failedAt,
                                               Duration retryInterval, int maxAttempts, String error) {
        if (attempt.attemptNumber() >= maxAttempts) {
            return blockCurrentAttempt(attempt, failedAt, error, "ATTEMPTS_EXHAUSTED");
        }
        int updated = repository.scheduleRetry(
            attempt.eventId(), attempt.attemptNumber(), failedAt.plus(retryInterval), error, failedAt);
        return updated == 1 ? FailureResult.pending() : FailureResult.stale();
    }

    @Transactional
    public FailureResult markBlocked(OrderConfirmedOutboxAttempt attempt, ZonedDateTime blockedAt, String error,
                                     String reason) {
        return blockCurrentAttempt(attempt, blockedAt, error, reason);
    }

    @Transactional
    public ResumeResult resumeBlocked(String eventId, ZonedDateTime resumedAt) {
        OrderConfirmedOutboxJpaEntity row = repository.findByEventId(eventId)
            .orElseThrow(() -> new IllegalStateException("수동 재시도할 Outbox를 찾을 수 없습니다. eventId=" + eventId));
        row.resumeBlocked(resumedAt);
        repository.saveAndFlush(row);
        return new ResumeResult(row.getEventId(), row.getOrderId(), row.getAttemptCount(), row.getCycleAttemptCount());
    }

    private FailureResult blockCurrentAttempt(OrderConfirmedOutboxAttempt attempt, ZonedDateTime blockedAt,
                                              String error, String reason) {
        if (repository.markBlocked(attempt.eventId(), attempt.attemptNumber(), error, blockedAt) != 1) {
            return FailureResult.stale();
        }
        return FailureResult.blocked(attempt.eventId(), attempt.orderId(), reason);
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
