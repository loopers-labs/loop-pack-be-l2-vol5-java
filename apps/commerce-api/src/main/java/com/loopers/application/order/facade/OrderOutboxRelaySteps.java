package com.loopers.application.order.facade;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.repository.OrderConfirmedOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/** relay 회차의 DB 단계. 각 단계가 짧은 TX 라 send 를 기다리는 동안 행 잠금 · 커넥션을 쥐지 않음 (7주차 D-104) */
@RequiredArgsConstructor
@Component
public class OrderOutboxRelaySteps {

    private final OrderConfirmedOutboxRepository outboxRepository;

    @Transactional(readOnly = true)
    public List<Long> findDueIds(ZonedDateTime now, int limit) {
        return outboxRepository.findDue(now, limit).stream()
            .map(OrderConfirmedOutbox::getId)
            .toList();
    }

    /** 조회 뒤 재개 · 다른 시도로 바뀌었을 수 있어 다시 읽고, 아직 차례일 때만 시작함 */
    @Transactional
    public Optional<OrderConfirmedOutbox> startAttempt(Long id, ZonedDateTime now, Duration lease) {
        return outboxRepository.findById(id)
            .filter(outbox -> outbox.isDue(now))
            .map(outbox -> {
                outbox.startAttempt(now, lease);
                return outbox;
            });
    }

    @Transactional
    public void markPublished(Long id, ZonedDateTime now) {
        get(id).markPublished(now);
    }

    @Transactional
    public void retryAt(Long id, ZonedDateTime nextAttemptAt, String error) {
        get(id).retryAt(nextAttemptAt, error);
    }

    @Transactional
    public void block(Long id, String error) {
        get(id).block(error);
    }

    private OrderConfirmedOutbox get(Long id) {
        return outboxRepository.findById(id)
            .orElseThrow(() -> new IllegalStateException("Outbox 가 없음. id=" + id));
    }
}
