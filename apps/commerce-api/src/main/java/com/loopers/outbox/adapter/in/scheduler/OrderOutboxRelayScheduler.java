package com.loopers.outbox.adapter.in.scheduler;

import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * relay 한 회차의 방아쇠 (docs/week7/plan.md D-1). 언제 돌릴지만 안다.
 * outbox.relay.scheduler.enabled=false(test 프로필)면 빈을 만들지 않는다 — 테스트는 relay를 직접 부른다.
 */
@Slf4j
@ConditionalOnProperty(name = "outbox.relay.scheduler.enabled", havingValue = "true")
@RequiredArgsConstructor
@Component
public class OrderOutboxRelayScheduler {

    public static final String LOCK_NAME = "order-outbox-relay";

    private final OrderOutboxRelayUseCase relay;

    @Scheduled(fixedDelayString = "${outbox.relay.tick:1s}", initialDelayString = "${outbox.relay.tick:1s}")
    public void tick() {
        RelayResult result = relay.relayOnce();
        if (result.selected() > 0) {
            log.info("relay 회차: 고름 {} · PUBLISHED {} · 멈춘 행 {}", result.selected(), result.published(), result.stoppedAt());
        }
    }
}
