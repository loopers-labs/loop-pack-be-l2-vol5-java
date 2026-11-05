package com.loopers.application.order.facade;

import com.loopers.application.order.dto.OrderBackfillInfo;
import com.loopers.application.order.dto.OrderOutboxResumeInfo;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.repository.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

/** 사람이 로컬에서 부르는 Outbox 운영 동작. HTTP 로 노출하지 않음 (7주차 설계 5.2) */
@RequiredArgsConstructor
@Component
public class OrderOutboxOperations {

    private final OrderOutboxRelay relay;
    private final OrderService orderService;
    private final OrderConfirmedOutboxRepository outboxRepository;
    private final Clock clock;

    /** 스케줄과 같은 잠금을 써 겹치면 건너뜀 */
    public boolean runOnce() {
        return relay.runOnce();
    }

    @Transactional
    public OrderOutboxResumeInfo resume(String eventId) {
        OrderConfirmedOutbox outbox = outboxRepository.findByEventId(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Outbox 가 없음. eventId=" + eventId));
        if (outbox.getStatus() != OrderOutboxStatus.BLOCKED) {
            return new OrderOutboxResumeInfo(eventId, outbox.getStatus(), false);
        }
        outbox.resume(ZonedDateTime.now(clock));
        return new OrderOutboxResumeInfo(eventId, outbox.getStatus(), true);
    }

    /** 대상은 명시한 주문뿐이고 전체 소급은 하지 않음. 주문마다 따로 commit 함 (D-112) */
    public List<OrderBackfillInfo> backfill(List<Long> orderIds) {
        return orderIds.stream()
            .map(this::backfill)
            .toList();
    }

    private OrderBackfillInfo backfill(Long orderId) {
        try {
            return OrderBackfillInfo.of(orderId, orderService.backfillConfirmed(orderId));
        } catch (RuntimeException e) {
            return OrderBackfillInfo.failed(orderId, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
