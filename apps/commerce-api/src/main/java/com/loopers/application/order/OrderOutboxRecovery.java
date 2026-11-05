package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderOutboxRecorder;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Outbox 도입 전에 확정된 주문의 전달 대상을 저장된 사실로 보충한다. PG·예약·주문 상태는 바꾸지 않는다. */
@RequiredArgsConstructor
@Component
public class OrderOutboxRecovery {
    private final OrderService orders;
    private final OrderOutboxRecorder recorder;

    /** 대상 주문의 Outbox를 보충하고, 새로 추가했으면 true를 돌려준다. 원본 사실이 부족하면 예외를 던진다. */
    @Transactional
    public boolean recover(Long orderId) {
        OrderModel order = orders.getForUpdate(orderId);
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null) {
            throw new IllegalStateException("확정 시각이 있는 확정 주문만 복구할 수 있습니다: orderId=" + orderId);
        }
        return recorder.recordIfAbsent(order.toConfirmedFact());
    }
}
