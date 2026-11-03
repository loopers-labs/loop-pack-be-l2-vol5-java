package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderOutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 인스턴스마다 켜기 설정으로 relay를 띄울지 정한다. 여러 인스턴스면 한 대만 켠다(D116). fixedDelay라 앞 회차가 끝난 뒤 다음 회차를 시작한다.
@Component
@ConditionalOnProperty(name = "commerce.outbox.relay.enabled", havingValue = "true")
public class OrderOutboxRelayScheduler {
    private final OrderOutboxRelay orderOutboxRelay;

    public OrderOutboxRelayScheduler(OrderOutboxRelay orderOutboxRelay) {
        this.orderOutboxRelay = orderOutboxRelay;
    }

    @Scheduled(fixedDelayString = "${commerce.outbox.relay.fixed-delay:PT1S}")
    public void runOnce() {
        orderOutboxRelay.runOnce();
    }
}
