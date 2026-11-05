package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * OrderConfirmed Outbox 행을 만드는 단 하나의 지점(docs/week7/decisions.md D-5, INV-706). 확정 TX와 6주차 누락 복구가
 * 모두 여기를 부른다 — 같은 eventId에 다른 payload가 나가면 consumer가 중복이 아니라 conflict로 기록한다(W6 D-18).
 *
 * - 재료는 저장된 주문 사실뿐이다: 확정 시각·품목(OrderConfirmedPayload.from)과 orderId로 계산한 eventId. PG는 부르지 않는다.
 * - payload는 여기서 한 번 계약 JSON 문자열로 만들고, 그 뒤로는 다시 만들지 않는다(D-1, INV-702).
 * - message_key는 6주차와 같이 orderId의 문자열이다 — 같은 주문은 같은 partition으로 간다.
 * - 만들기만 하고 저장하지 않는다. 저장은 호출자의 트랜잭션에서 한다(확정 TX라면 D-6).
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedOutbox {

    private final OrderConfirmedSerializer serializer;

    /**
     * @param firstAttemptAt relay가 처음 집을 수 있는 시각(next_attempt_at의 첫 값). payload에는 들어가지 않는다 —
     *                       created_at은 BaseEntity가 채운다(D-16).
     */
    public OutboxEvent create(Order order, ZonedDateTime firstAttemptAt) {
        OrderConfirmedPayload payload = OrderConfirmedPayload.from(order);
        return OutboxEvent.pending(
            payload.eventId(),
            payload.orderId(),
            OrderConfirmedPayload.EVENT_TYPE,
            OrderConfirmedPayload.SCHEMA_VERSION,
            String.valueOf(payload.orderId()),
            serializer.write(payload),
            firstAttemptAt
        );
    }
}
