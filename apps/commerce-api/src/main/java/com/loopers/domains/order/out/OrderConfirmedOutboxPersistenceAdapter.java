package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.app.port.OutboxMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// 확정 트랜잭션에 참여해 insert한다(별도 트랜잭션·commit 뒤 기록이면 확정과 기록이 따로 남을 수 있다).
// 바로 flush해 eventId 유일 제약 위반·DB 오류가 commit 때가 아니라 이 호출에서 드러나게 하고, 예외는 잡지 않고 올린다.
// relay의 상태 기록은 PENDING 행에만 적용되는 조건부 갱신이고 영향 행 수를 돌려준다. PUBLISHED·BLOCKED는 되돌리지 않는다(D115).
@Repository
public class OrderConfirmedOutboxPersistenceAdapter implements OrderConfirmedOutbox {
    private static final int LAST_ERROR_LENGTH = 500;

    private final OrderConfirmedOutboxJpaRepository orderConfirmedOutboxJpaRepository;
    private final Clock clock;

    public OrderConfirmedOutboxPersistenceAdapter(OrderConfirmedOutboxJpaRepository orderConfirmedOutboxJpaRepository,
        Clock clock) {
        this.orderConfirmedOutboxJpaRepository = orderConfirmedOutboxJpaRepository;
        this.clock = clock;
    }

    @Override
    public void append(OrderConfirmedPayload payload) {
        String message = OrderConfirmedMessageSerializer.serialize(payload);
        orderConfirmedOutboxJpaRepository.saveAndFlush(OrderConfirmedOutboxJpaEntity.pending(payload.eventId(),
            payload.orderId(), OrderConfirmedPayload.EVENT_TYPE, OrderConfirmedPayload.SCHEMA_VERSION, payload.key(), message,
            clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxMessage> findDue(Instant now, int limit) {
        return orderConfirmedOutboxJpaRepository.findDue(now, PageRequest.of(0, limit)).stream()
            .map(entity -> new OutboxMessage(entity.id(), entity.eventId(), entity.orderId(), entity.messageKey(),
                entity.payload(), entity.attemptCount(), entity.rowFailureCount()))
            .toList();
    }

    @Override
    @Transactional
    public int markPublished(long id, Instant publishedAt) {
        return orderConfirmedOutboxJpaRepository.markPublished(id, publishedAt);
    }

    @Override
    @Transactional
    public int markBrokerFailure(long id, Instant nextAttemptAt, String error) {
        return orderConfirmedOutboxJpaRepository.markBrokerFailure(id, nextAttemptAt, truncate(error));
    }

    @Override
    @Transactional
    public int markRowFailure(long id, Instant nextAttemptAt, String error, boolean blocked) {
        return orderConfirmedOutboxJpaRepository.markRowFailure(id, nextAttemptAt, truncate(error),
            blocked ? "BLOCKED" : "PENDING");
    }

    @Override
    @Transactional
    public int resumeBlocked(String eventId, Instant now) {
        return orderConfirmedOutboxJpaRepository.resumeBlocked(eventId, now);
    }

    // last_error 열은 500자다. 원인 메시지가 길어도 상태 기록이 실패하지 않게 자른다.
    private static String truncate(String error) {
        if (error == null || error.length() <= LAST_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, LAST_ERROR_LENGTH);
    }
}
