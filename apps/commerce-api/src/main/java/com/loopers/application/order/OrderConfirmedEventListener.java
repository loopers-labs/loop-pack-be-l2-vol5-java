package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 최초 PG 확정 TX 가 실제로 commit 된 뒤에만 사건을 발행 포트에 넘긴다. TX 밖에서 등록된 사건은 실행하지 않는다.
 * 이미 끝난 commit 을 되돌릴 수 없으므로 실패는 로그로만 드러내고 확정 결과를 바꾸지 않는다. DB 조회·재시도는 하지 않는다.
 */
@Slf4j
@Component
public class OrderConfirmedEventListener {

    private final OrderPublicationProbe probe;
    private final OrderEventPublisher publisher;
    private final String topic;

    public OrderConfirmedEventListener(OrderPublicationProbe probe, OrderEventPublisher publisher,
                                       @Value("${commerce.order-events.topic}") String topic) {
        this.probe = probe;
        this.publisher = publisher;
        this.topic = topic;
    }

    /**
     * 포트 구현은 자기 오류를 직접 기록하고 정상 반환하므로, 여기서는 probe 와 포트 외곽의 예기치 않은 오류만 기록한다.
     * 치명적인 Error 는 정상 성공처럼 숨기지 않도록 잡지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void on(OrderConfirmed event) {
        try {
            probe.beforeSend(event);
            publisher.publish(event);
        } catch (RuntimeException failure) {
            log.warn("ORDER_CONFIRMED_SEND_FAILURE eventId={} orderId={} topic={} stage=LISTENER_CALL exceptionType={} causeType={}",
                event.eventId(), event.orderId(), topic, failure.getClass().getName(), causeType(failure));
        }
    }

    private static String causeType(Throwable failure) {
        return failure.getCause() == null ? "none" : failure.getCause().getClass().getName();
    }
}
