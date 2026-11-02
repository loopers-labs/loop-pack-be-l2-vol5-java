package com.loopers.application.order.listener;

import com.loopers.domain.order.gateway.OrderEventPublisher;
import com.loopers.domain.order.vo.OrderConfirmed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 확정 commit 이 성공한 뒤에만 보냄. commit 전에 보내면 rollback 된 확정의 이벤트가 남음 (6주차 D-72).
 * commit 과 전송은 원자적이지 않아 그 사이 누락은 남음 (설계 3.6, 7주차 Outbox)
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedListener {

    private final OrderEventPublisher orderEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(OrderConfirmed event) {
        orderEventPublisher.publish(event);
    }
}
