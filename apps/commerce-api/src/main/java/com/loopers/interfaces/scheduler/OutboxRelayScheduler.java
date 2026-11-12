package com.loopers.interfaces.scheduler;

import com.loopers.application.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * relay 회차를 주기적으로 실행한다. fixedDelay 라 회차가 끝난 뒤 간격만큼 쉬어, 브로커 장애로 회차가 길어져도 쉼 없이
 * 다시 붙지 않는다. 켜진 인스턴스에서만 만든다.
 */
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true")
public class OutboxRelayScheduler {

    private final OutboxRelay outboxRelay;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay}")
    public void run() {
        outboxRelay.relayOnce();
    }
}
