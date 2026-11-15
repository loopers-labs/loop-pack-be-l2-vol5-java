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

    /** 누락 복구 한 건의 결과. 충돌·원본 사실 부족은 결과가 아니라 예외다. */
    public enum BackfillResult {
        /** Outbox 행이 없어 저장된 확정 사실로 PENDING 한 행을 추가했다. */
        ADDED,
        /** 같은 사실을 담은 행이 이미 있다. 아무것도 바꾸지 않았다. */
        ALREADY_PRESENT
    }

    private final OrderOutboxRepository orderOutboxRepository;
    private final OrderEventSerializer orderEventSerializer;
    private final OrderRepository orderRepository;

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

    /**
     * 누락 복구 (W7 design.md 4-4절). 확정 트랜잭션에서 Outbox를 남기지 못한 주문(W6 직접 발행 시절 등)의 <b>저장된 확정 사실</b>로 Outbox 행을 채운다.
     *
     * <ul>
     *   <li>eventId·confirmedAt·품목은 저장된 주문에서 확정 경로와 같은 방식으로 만든다. 복구 시각·현재 상품을 넣지 않는다. {@code recordedAt}은 relay 대상이 되는 시각일 뿐이다.</li>
     *   <li>행이 없을 때만 PENDING 한 행을 추가한다. 이미 있으면 같은 사실인지만 확인하고 아무것도 바꾸지 않는다(이미 보낸 행도 다시 PENDING으로 돌리지 않는다).</li>
     *   <li>주문이 없거나, 확정되지 않았거나 확정 시각이 없거나(원본 사실 부족), 기존 행과 내용이 다르면(충돌) 추측해 채우거나 덮어쓰지 않고 {@link IllegalStateException}으로 드러낸다.</li>
     *   <li>주문·결제·예약은 읽기만 한다. PG를 부르거나 주문을 다시 확정하지 않는다.</li>
     * </ul>
     */
    @Transactional
    public BackfillResult backfill(Long orderId, Instant recordedAt) {
        OrderModel order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalStateException("복구 대상 주문이 없습니다. orderId=" + orderId));
        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);
        String payload = orderEventSerializer.serialize(event);
        Optional<OrderOutboxModel> existing = orderOutboxRepository.findByEventId(event.eventId());
        if (existing.isPresent()) {
            if (!existing.get().holdsSameFact(event, payload)) {
                throw new IllegalStateException("기존 Outbox 행이 저장된 확정 사실과 다릅니다. 고치지 않고 멈춘다. eventId=" + event.eventId()
                    + ", orderId=" + orderId);
            }
            return BackfillResult.ALREADY_PRESENT;
        }
        orderOutboxRepository.save(OrderOutboxModel.pending(event, payload, recordedAt));
        return BackfillResult.ADDED;
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

    @Transactional(readOnly = true)
    public OrderOutboxBacklog backlog() {
        return orderOutboxRepository.pendingBacklog();
    }
}
