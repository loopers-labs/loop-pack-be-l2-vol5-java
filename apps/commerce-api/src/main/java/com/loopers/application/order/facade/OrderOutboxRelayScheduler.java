package com.loopers.application.order.facade;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 운영에서는 인스턴스 하나만 켬. 테스트는 끄고 회차를 직접 부름 (7주차 D-103) */
@ConditionalOnProperty(name = "order-outbox.relay.enabled", havingValue = "true")
@RequiredArgsConstructor
@Component
public class OrderOutboxRelayScheduler {

    private final OrderOutboxRelay relay;

    /** fixedDelay 는 앞 회차가 끝난 뒤 다음을 잡아 같은 스케줄끼리 겹치지 않음 */
    @Scheduled(fixedDelayString = "${order-outbox.relay.interval-ms}")
    public void run() {
        relay.runOnce();
    }
}
