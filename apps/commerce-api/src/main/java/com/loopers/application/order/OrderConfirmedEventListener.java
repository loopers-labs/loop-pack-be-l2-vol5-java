package com.loopers.application.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 확정 commit 뒤 확정 사실을 발행한다. 확정 트랜잭션이 rollback 되면 불리지 않는다.
 * 발행 실패는 주문 결과에 섞지 않는다: 예외를 삼키고 로그만 남긴다 (AFTER_COMMIT 의 예외는 commit 한 호출자에게 올라가, 이미 확정된 주문의 응답을 실패로 바꾼다).
 * 전송 결과(성공 위치·최종 실패)는 OrderConfirmedPublisher 구현이 남긴다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderConfirmedEventListener {
    private final OrderConfirmedPublisher orderConfirmedPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderConfirmedEvent event) {
        try {
            orderConfirmedPublisher.publish(event);
        } catch (RuntimeException e) {
            log.error("OrderConfirmed 발행 실패, 전송 전 [eventId = {}, orderId = {}]", event.eventId(), event.orderId(), e);
        }
    }
}
