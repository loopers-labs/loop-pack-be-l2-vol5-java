package com.loopers.domain.order;

import java.util.Optional;

/**
 * 주문 확정 Outbox 저장소 (7주차 설계 D11). 이번 범위(과제 1~2절)는 확정 TX 의 insert 와 조회만이다.
 * relay 가 쓰는 PENDING 조회·상태 갱신은 relay 설계(3절)에서 추가한다.
 */
public interface OrderConfirmedOutboxRepository {

    OrderConfirmedOutbox save(OrderConfirmedOutbox outbox);

    Optional<OrderConfirmedOutbox> findByEventId(String eventId);

    Optional<OrderConfirmedOutbox> findByOrderId(Long orderId);
}
