package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmedOutboxModel;
import com.loopers.domain.order.OrderConfirmedOutboxModel.Transition;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * relay 의 짧은 TX 경계(설계 W7.3-2·W7.3-4). relay 가 별도 bean 프록시로 호출하며 각 메서드가 Outbox 행 하나만 잠그고 끝난다.
 * 외부 전송·대기는 이 TX 안에 없다. 상태 규칙은 모델 행동에 있고 이 서비스는 조회·시각 계산·저장만 연결한다.
 */
@Component
public class OrderOutboxRelayTransactionService {

    public static final String UNCONFIRMED_ATTEMPT_LIMIT = "UNCONFIRMED_ATTEMPT_LIMIT";

    public enum BeginOutcome {
        STARTED,
        NOT_FOUND,
        NOT_DUE,
        ALREADY_PUBLISHED,
        BLOCKED,
        /** 시작 전 회차 예산이 이미 소진돼 send 없이 BLOCKED·UNCONFIRMED_ATTEMPT_LIMIT 로 바꿨다. */
        LIMIT_BLOCKED
    }

    /** STARTED 만 commit 된 시도 snapshot 을 가진다. */
    public record BeginResult(BeginOutcome outcome, Optional<OrderOutboxAttempt> attempt) {

        public BeginResult {
            if ((outcome == BeginOutcome.STARTED) != attempt.isPresent()) {
                throw new IllegalArgumentException("STARTED 만 시도 snapshot 을 가집니다.");
            }
        }

        static BeginResult skipped(BeginOutcome outcome) {
            return new BeginResult(outcome, Optional.empty());
        }
    }

    /** 상태 TX 결과. transition 이 APPLIED 가 아니면 행을 바꾸지 않았다. status 는 TX 안에서 본 결과 상태다. */
    public record StateChange(Transition transition, OrderOutboxStatus status) {
    }

    private final OrderConfirmedOutboxRepository repository;
    private final OrderOutboxRetryPolicy policy;

    public OrderOutboxRelayTransactionService(OrderConfirmedOutboxRepository repository, OrderOutboxRetryPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public List<Long> findDueIds(ZonedDateTime now, int limit) {
        return repository.findPendingDueIds(now, limit);
    }

    /** 명시 eventId 의 row ID 만 복사한다. Entity 를 TX 밖으로 내보내지 않는다. */
    @Transactional(readOnly = true)
    public Optional<Long> findIdByEventId(String eventId) {
        return repository.findByEventId(eventId).map(OrderConfirmedOutboxModel::getId);
    }

    /**
     * 행 잠금 뒤 PENDING·due·예산을 확인하고 두 횟수와 provisional 다음 시각을 commit 한다. 반환 snapshot 은 commit 뒤의 raw 값이다.
     * 회차 예산이 이미 소진된 PENDING(이전 시도 결과 불명)은 send 없이 BLOCKED 로 바꾼다.
     */
    @Transactional
    public BeginResult beginAttempt(long id, ZonedDateTime now) {
        Optional<OrderConfirmedOutboxModel> found = repository.findForUpdateById(id);
        if (found.isEmpty()) {
            return BeginResult.skipped(BeginOutcome.NOT_FOUND);
        }
        OrderConfirmedOutboxModel outbox = found.get();
        outbox.requireDeliveryState();
        if (outbox.getStatus() == OrderOutboxStatus.PUBLISHED) {
            return BeginResult.skipped(BeginOutcome.ALREADY_PUBLISHED);
        }
        if (outbox.getStatus() == OrderOutboxStatus.BLOCKED) {
            return BeginResult.skipped(BeginOutcome.BLOCKED);
        }
        if (outbox.getNextAttemptAt().isAfter(now)) {
            return BeginResult.skipped(BeginOutcome.NOT_DUE);
        }
        if (outbox.getCycleAttemptCount() >= policy.maxCycleAttempts()) {
            requireApplied(outbox.blockInvalid(outbox.getAttemptCount(), UNCONFIRMED_ATTEMPT_LIMIT));
            repository.saveAndFlush(outbox);
            return BeginResult.skipped(BeginOutcome.LIMIT_BLOCKED);
        }
        outbox.beginAttempt(now, policy.nextAttemptAt(now, outbox.getCycleAttemptCount() + 1));
        repository.saveAndFlush(outbox);
        return new BeginResult(BeginOutcome.STARTED, Optional.of(new OrderOutboxAttempt(outbox.getId(), outbox.getAttemptCount(),
            outbox.getCycleAttemptCount(), outbox.storedMessage())));
    }

    @Transactional
    public StateChange markPublished(long id, long attemptNo, ZonedDateTime now) {
        OrderConfirmedOutboxModel outbox = lock(id);
        return save(outbox, outbox.markPublished(attemptNo, now));
    }

    /** 관측 now 기준으로 다음 시각을 다시 계산한다. 회차 한도에 닿았으면 모델이 BLOCKED 로 바꾼다. */
    @Transactional
    public StateChange recordFailure(long id, long attemptNo, String safeError, ZonedDateTime now) {
        OrderConfirmedOutboxModel outbox = lock(id);
        ZonedDateTime nextAt = policy.nextAttemptAt(now, Math.max(1, outbox.getCycleAttemptCount()));
        return save(outbox, outbox.recordFailure(attemptNo, safeError, nextAt, policy.maxCycleAttempts()));
    }

    @Transactional
    public StateChange blockInvalid(long id, long attemptNo, String safeError) {
        OrderConfirmedOutboxModel outbox = lock(id);
        return save(outbox, outbox.blockInvalid(attemptNo, safeError));
    }

    private OrderConfirmedOutboxModel lock(long id) {
        return repository.findForUpdateById(id)
            .orElseThrow(() -> new IllegalStateException("시도 중인 Outbox 행이 없습니다. [outboxId=" + id + "]"));
    }

    /** 변경이 있을 때만 flush 한다. 변경 없는 결과는 감사 시각도 바꾸지 않는다. */
    private StateChange save(OrderConfirmedOutboxModel outbox, Transition transition) {
        if (transition == Transition.APPLIED) {
            repository.saveAndFlush(outbox);
        }
        return new StateChange(transition, outbox.getStatus());
    }

    private static void requireApplied(Transition transition) {
        if (transition != Transition.APPLIED) {
            throw new IllegalStateException("시작 전 회차 예산 차단이 적용되지 않았습니다. [transition=" + transition + "]");
        }
    }
}
