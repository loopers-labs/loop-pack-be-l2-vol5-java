package com.loopers.application.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * W6 누락 복구용 backfill (7주차 설계 D34·D35). 이미 CONFIRMED 로 저장된 주문의 사실(confirmedAt·품목)에서 OrderConfirmed Outbox 를 보충한다.
 *
 * <p>이것은 확정 경로를 건너뛰는 스위치가 아니라, 이미 확정된 주문의 저장 사실로 Outbox 만 보충하는 복구 경로다.
 * 주문·시도·예약·PG 는 읽기만 하고 바꾸지 않으며, 새 결제를 만들지 않는다. 입구는 테스트 도구만이다 (D30). HTTP·Runner 로 노출하지 않는다.
 *
 * <ul>
 *   <li>대상은 orderId 한 건씩 명시한다. "Outbox 없는 CONFIRMED 주문 전체" 자동 소급은 하지 않는다</li>
 *   <li>payload 는 확정 때와 같은 코드({@link OrderConfirmedEvent#of} + 같은 ObjectMapper)로 다시 만든다. 현재 상품을 재조회하지 않는다</li>
 *   <li>Outbox 가 이미 있으면 eventId·orderId·messageKey·payload 문자열이 모두 같을 때만 ALREADY_PRESENT, 하나라도 다르면 CONFLICT</li>
 *   <li>원본 사실 부족(주문 없음·CONFIRMED 아님·confirmedAt 없음·품목 0)은 추측하지 않고 각각 오류로 드러낸다</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxBackfill {

    /** CREATED: Outbox 가 없어 한 행 추가. ALREADY_PRESENT: 이미 있고 내용이 일치해 그대로 둠. */
    public enum Result {
        CREATED, ALREADY_PRESENT
    }

    private final OrderRepository orderRepository;
    private final OrderConfirmedOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public Result backfill(Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 주문이 없어 Outbox 를 보충할 수 없습니다."));
        if (order.getStatus() != Order.OrderStatus.CONFIRMED) {
            throw new CoreException(ErrorType.CONFLICT,
                "[orderId = " + orderId + "] CONFIRMED 주문이 아니라 Outbox 를 보충할 수 없습니다. 현재 상태: " + order.getStatus());
        }
        if (order.getConfirmedAt() == null) {
            throw new CoreException(ErrorType.CONFLICT,
                "[orderId = " + orderId + "] 확정 시각(confirmedAt)이 없어 사건을 복원할 수 없습니다. 이전 POINT 확정 주문은 대상이 아닙니다.");
        }
        if (order.getItems().isEmpty()) {
            throw new CoreException(ErrorType.CONFLICT, "[orderId = " + orderId + "] 저장된 품목이 없어 사건을 복원할 수 없습니다.");
        }

        OrderConfirmedEvent event = OrderConfirmedEvent.of(orderId, order.getConfirmedAt(), order.getItems());
        String payload = serialize(event);

        Optional<OrderConfirmedOutbox> existing = outboxRepository.findByOrderId(orderId)
            .or(() -> outboxRepository.findByEventId(event.eventId()));
        if (existing.isPresent()) {
            verifySame(existing.get(), event, payload);
            log.info("Outbox backfill 생략: 이미 있고 내용이 같다. eventId={}, orderId={}, status={}",
                event.eventId(), orderId, existing.get().getStatus());
            return Result.ALREADY_PRESENT;
        }

        // 확정 경로(applyFinal)와 같은 규칙으로 밀리초까지만 쓴다 (W6 D2)
        ZonedDateTime now = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        outboxRepository.save(OrderConfirmedOutbox.pending(event, payload, now));
        log.info("Outbox backfill 추가: eventId={}, orderId={}, confirmedAt={}, items={}", event.eventId(), orderId, event.confirmedAt(), event.items().size());
        return Result.CREATED;
    }

    private static void verifySame(OrderConfirmedOutbox existing, OrderConfirmedEvent event, String payload) {
        if (!Objects.equals(existing.getEventId(), event.eventId())
            || !Objects.equals(existing.getOrderId(), event.orderId())
            || !Objects.equals(existing.getMessageKey(), event.key())
            || !Objects.equals(existing.getPayload(), payload)) {
            throw new CoreException(ErrorType.CONFLICT,
                "[eventId = " + event.eventId() + "] 기존 Outbox 와 저장된 확정 사실이 다릅니다. 추측하지 않고 중단합니다.");
        }
    }

    private String serialize(OrderConfirmedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "[eventId = " + event.eventId() + "] Outbox payload 직렬화에 실패했습니다.");
        }
    }
}
