package com.loopers.domain.order;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface OrderRepository {
    OrderModel save(OrderModel order);

    Optional<OrderModel> find(Long id);

    /** 결제 시작·결과 반영용: 비관적 쓰기 락(SELECT ... FOR UPDATE). 주문 행을 다른 락보다 먼저 잡는다 (주문 → 재고 → 상품). */
    Optional<OrderModel> findForUpdate(Long id);

    /**
     * 결제 대기 만료용: startedBefore 이전에 결제를 시작한 AWAITING_PAYMENT 주문 하나를 결제 시작 순으로 잠근다 (FOR UPDATE SKIP LOCKED).
     * 다른 트랜잭션이 잠근 주문은 기다리지 않고 건너뛴다.
     */
    Optional<OrderModel> findExpiredAwaitingForUpdate(ZonedDateTime startedBefore);
}
