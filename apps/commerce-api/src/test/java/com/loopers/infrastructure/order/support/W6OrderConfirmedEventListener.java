package com.loopers.infrastructure.order.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.loopers.domain.order.OrderConfirmed;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * W6 보존(설계 W7.4-1). 출처: 53f912b apps/commerce-api/src/main/java/com/loopers/application/order/OrderConfirmedEventListener.java.
 * 허용 변경: package·타입 이름, 테스트 소스에 Lombok 처리가 없어 @Slf4j 를 같은 SLF4J Logger 필드로 풀어 씀, 포트·probe 타입을 W6 보존 타입으로 연결, {@code @Component} 제거(W6 조립이 bean
 * orderConfirmedEventListener 로 명시 등록). AFTER_COMMIT·fallbackExecution=false·beforeSend 위치·오류 처리는 원본 그대로다.
 *
 * <p>최초 PG 확정 TX 가 실제로 commit 된 뒤에만 사건을 발행 포트에 넘긴다. TX 밖에서 등록된 사건은 실행하지 않는다.
 * 이미 끝난 commit 을 되돌릴 수 없으므로 실패는 로그로만 드러내고 확정 결과를 바꾸지 않는다. DB 조회·재시도는 하지 않는다.
 */
public class W6OrderConfirmedEventListener {

    private static final Logger log = LoggerFactory.getLogger(W6OrderConfirmedEventListener.class);

    private final W6OrderPublicationProbe probe;
    private final W6OrderEventPublisher publisher;
    private final String topic;

    public W6OrderConfirmedEventListener(W6OrderPublicationProbe probe, W6OrderEventPublisher publisher,
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
