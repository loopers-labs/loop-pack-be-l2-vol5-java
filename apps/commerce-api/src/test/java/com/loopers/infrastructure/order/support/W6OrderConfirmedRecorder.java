package com.loopers.infrastructure.order.support;

import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.domain.order.OrderConfirmed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * W6 준비 컨텍스트 전용 recorder(설계 W7.4-1). 53f912b PaymentTransactionService.applyObservation 의
 * {@code eventPublisher.publishEvent(confirmed)} 를 같은 위치(저장 뒤·BEFORE_COMMIT probe 전)에서 그대로 수행한다.
 * 실제 Spring 이벤트를 TX 안에 등록할 뿐이며 전송은 보존한 AFTER_COMMIT listener 가 맡는다. 성공 사실·Outbox 를 직접 쓰지 않는다.
 */
public class W6OrderConfirmedRecorder implements OrderConfirmedRecorder {

    private final ApplicationEventPublisher eventPublisher;

    public W6OrderConfirmedRecorder(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(OrderConfirmed event) {
        // 사건을 TX 안에 등록할 뿐 전송이 아니다. commit 전에 실패해 rollback 되면 listener 는 실행되지 않는다.
        eventPublisher.publishEvent(event);
    }
}
