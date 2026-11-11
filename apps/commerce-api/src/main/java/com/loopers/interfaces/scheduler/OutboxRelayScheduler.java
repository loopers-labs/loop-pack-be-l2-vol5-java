package com.loopers.interfaces.scheduler;

import com.loopers.application.order.OrderConfirmedOutboxRelay;
import com.loopers.application.order.OutboxRelayProperties;
import com.loopers.config.scheduling.OutboxRelaySchedulingConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 단일 relay 의 주기 진입점 (7주차 설계 D22). PaymentReconcileScheduler 와 같은 모양으로 로직은 없다.
 * outbox.relay.enabled 가 true 일 때만 relay 의 한 회차(runOnce)를 호출한다. enabled=false 여도 이 빈은 만들어지며 테스트는 runOnce 를 직접 호출한다.
 * enabled 는 인스턴스별 로컬 설정이다. 여러 인스턴스가 같은 relay 를 동시에 켜지 않는 것은 이 설정으로만 막으며 분산 잠금이 아니다.
 */
@RequiredArgsConstructor
@Component
public class OutboxRelayScheduler {

    private final OrderConfirmedOutboxRelay relay;
    private final OutboxRelayProperties properties;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay}", scheduler = OutboxRelaySchedulingConfig.OUTBOX_RELAY_TASK_SCHEDULER)
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        relay.runOnce();
    }
}
