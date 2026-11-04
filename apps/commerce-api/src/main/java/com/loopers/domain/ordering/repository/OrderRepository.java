package com.loopers.domain.ordering.repository;

import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderStatus;
import java.util.Optional;

// 주문 저장소 인터페이스
public interface OrderRepository {
    Order insert(Order order);

    // 호출자의 준비 TX에 참여하며 성공 여부는 TX commit 후 확정된다.
    boolean prepareIfDraft(long orderId, long userId);

    // 호출자의 업무 TX에 참여한다. 성공일 때만 PG 기록을 함께 저장한다.
    boolean completePgIfPending(long orderId, OrderStatus terminalStatus, OrderRecord pgRecord);

    Optional<Order> findById(long orderId);

    // 비관적 쓰기 잠금으로 조회
    Optional<Order> findByIdForUpdate(long orderId);
}
