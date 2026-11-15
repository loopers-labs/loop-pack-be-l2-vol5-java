package com.loopers.interfaces.scheduler;

import com.loopers.application.order.OrderOutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox relay를 주기적으로 한 회차씩 실행한다 (W7 design.md 4절). {@code order-outbox.relay.scheduling-enabled=true}인 인스턴스에서만 만든다.
 * 한 회차가 끝난 뒤 fixed-delay만큼 쉬고 다음 회차를 시작하므로 tick이 겹치지 않고, 수동 실행과는 relay의 잠금으로 겹치지 않는다.
 */
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "order-outbox.relay.scheduling-enabled", havingValue = "true")
public class OrderOutboxRelayScheduler {

    private final OrderOutboxRelay orderOutboxRelay;

    @Scheduled(fixedDelayString = "${order-outbox.relay.fixed-delay}")
    public void relay() {
        orderOutboxRelay.runOnce();
    }
}
