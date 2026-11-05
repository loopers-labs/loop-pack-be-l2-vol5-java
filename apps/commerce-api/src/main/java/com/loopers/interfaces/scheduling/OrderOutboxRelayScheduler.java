package com.loopers.interfaces.scheduling;

import com.loopers.application.order.OrderOutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인스턴스 하나에서만 켠다. 여러 곳에서 켜면 같은 행을 동시에 보낼 수 있다.
 * relay가 있을 때(이벤트 전달 활성화)만 등록한다. 전달을 꺼도 Outbox 기록은 계속 남는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = {"commerce.order-events.enabled", "commerce.order-events.relay.scheduling-enabled"},
    havingValue = "true")
public class OrderOutboxRelayScheduler {
    private final OrderOutboxRelay relay;

    // 앞 실행이 끝난 뒤 기다려 실행이 겹치지 않는다.
    @Scheduled(fixedDelayString = "${commerce.order-events.relay.fixed-delay-ms:10000}")
    public void relay() {
        relay.runOnce();
    }
}
