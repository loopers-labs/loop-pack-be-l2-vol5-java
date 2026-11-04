package com.loopers.domain.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderOutboxService {

    private final OrderOutboxRepository orderOutboxRepository;
    private final OrderEventSerializer orderEventSerializer;

    /**
     * 확정 사실을 보낼 사실로 남긴다 (W7 design.md 3절). 주문을 확정하는 트랜잭션 안에서만 부를 수 있다.
     *
     * <p>{@code MANDATORY}라서 새 트랜잭션을 열지 않고, 트랜잭션 밖에서 부르면 예외다. 그래서 직렬화·저장이 실패하면 확정도 함께 rollback되고,
     * 확정이 rollback되면 이 행도 남지 않는다. {@code REQUIRES_NEW}나 commit 뒤 insert로 나뉘면 생기는 공백을 구조로 막는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderOutboxModel append(OrderConfirmedEvent event, Instant recordedAt) {
        String payload = orderEventSerializer.serialize(event);
        return orderOutboxRepository.save(OrderOutboxModel.pending(event, payload, recordedAt));
    }

    // ---- relay가 부르는 짧은 트랜잭션들 (W7 design.md 4절). 주문 확정 트랜잭션과 무관하고, broker를 기다리는 동안 열려 있지 않다 ----

    @Transactional(readOnly = true)
    public List<String> findDueEventIds(Instant now, int limit) {
        return orderOutboxRepository.findDuePendingEventIds(now, limit);
    }

    /** PENDING이면 시도를 기록하고 보낼 한 건을 돌려준다. 없거나 이미 PUBLISHED면 비어 있다. */
    @Transactional
    public Optional<OrderOutboxMessage> startAttempt(String eventId) {
        return orderOutboxRepository.findByEventId(eventId)
            .filter(OrderOutboxModel::startAttempt)
            .map(OrderOutboxMessage::of);
    }

    @Transactional
    public boolean markPublished(String eventId, Instant publishedAt) {
        return orderOutboxRepository.findByEventId(eventId)
            .map(outbox -> outbox.markPublished(publishedAt))
            .orElse(false);
    }

    @Transactional
    public boolean recordFailure(String eventId, int attempt, String error, Instant nextAttemptAt) {
        return orderOutboxRepository.findByEventId(eventId)
            .map(outbox -> outbox.recordFailure(attempt, error, nextAttemptAt))
            .orElse(false);
    }
}
