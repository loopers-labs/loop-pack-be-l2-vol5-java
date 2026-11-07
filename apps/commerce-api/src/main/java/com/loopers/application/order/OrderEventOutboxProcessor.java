package com.loopers.application.order;

import com.loopers.domain.order.OrderEventOutboxModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Outbox relay 의 DB 트랜잭션 단위. 전송 시작·발행 확인 저장·실패 기록·재개가 각각 짧은 트랜잭션 하나다.
 * broker 전송과 그 대기는 이 트랜잭션들 밖에서 OrderEventRelay 가 한다. 주문 확정 트랜잭션과도 따로다.
 */
@RequiredArgsConstructor
@Component
public class OrderEventOutboxProcessor {
    private final OrderService orderService;
    private final OutboxRelayProperties properties;

    /** 보낼 행의 저장된 값. relay 는 이 값만 보낸다 (주문·상품을 다시 읽지 않는다). */
    public record Claimed(String eventId, String messageKey, String payload, int attempt) {

        static Claimed of(OrderEventOutboxModel outbox) {
            return new Claimed(outbox.getEventId(), outbox.getMessageKey(), outbox.getPayload(), outbox.getAttemptCount());
        }
    }

    /** 재시도 시각이 된 PENDING 을 (next_attempt_at, id) 순으로 batchSize 건 골라 전송을 시작한다. */
    @Transactional
    public List<Claimed> claimDue(ZonedDateTime now) {
        return orderService.findDueOutbox(now, properties.batchSize()).stream()
            .map(outbox -> {
                outbox.startAttempt(now, properties.sendTimeout());
                return Claimed.of(outbox);
            })
            .toList();
    }

    /** 지정한 행 하나의 전송을 시작한다. 재시도 시각과 무관하다. PENDING 이 아니면 비어 있다 (PUBLISHED 는 보낼 것이 없고 BLOCKED 는 재개가 먼저다). */
    @Transactional
    public Optional<Claimed> claim(String eventId, ZonedDateTime now) {
        OrderEventOutboxModel outbox = orderService.getOutboxForUpdate(eventId);
        if (outbox.getStatus() != OutboxStatus.PENDING) {
            return Optional.empty();
        }
        outbox.startAttempt(now, properties.sendTimeout());
        return Optional.of(Claimed.of(outbox));
    }

    /** broker 확인을 저장한다. */
    @Transactional
    public void markPublished(String eventId, ZonedDateTime now, OrderEventSender.Sent sent) {
        orderService.getOutboxForUpdate(eventId).markPublished(now, sent.partition(), sent.offset());
    }

    /** 실패를 기록하고 바뀐 상태(PENDING 또는 BLOCKED)를 돌려준다. */
    @Transactional
    public OutboxStatus recordFailure(String eventId, ZonedDateTime now, String error) {
        OrderEventOutboxModel outbox = orderService.getOutboxForUpdate(eventId);
        outbox.recordFailure(now, error, properties.retryDelayAfter(outbox.getFailureCount() + 1), properties.maxFailures());
        return outbox.getStatus();
    }

    /** BLOCKED 면 같은 행을 PENDING 으로 되돌리고 true. 아니면 그대로 두고 false. */
    @Transactional
    public boolean resume(String eventId, ZonedDateTime now) {
        OrderEventOutboxModel outbox = orderService.getOutboxForUpdate(eventId);
        if (!outbox.isBlocked()) {
            return false;
        }
        outbox.resume(now);
        return true;
    }
}
