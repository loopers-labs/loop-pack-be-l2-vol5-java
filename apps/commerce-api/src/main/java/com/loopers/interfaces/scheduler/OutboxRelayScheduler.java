package com.loopers.interfaces.scheduler;

import com.loopers.application.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox relay의 시간 입구(docs/week7/decisions.md D-4). 앞 회차가 끝난 뒤 간격을 두고 다시 돈다(fixedDelay) — 회차가 겹치지 않는다.
 * 기본은 꺼져 있고 한 인스턴스에서만 켠다(outbox.relay.enabled=true). 실수로 여러 대가 켜져도 조건부 UPDATE가 같은 행을 두 번
 * 집지 못하게 막는다(INV-704). 테스트는 이 스케줄에 기대지 않고 relay를 직접 부른다.
 */
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true")
@RequiredArgsConstructor
@Component
public class OutboxRelayScheduler {

    private final OutboxRelay outboxRelay;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay:PT1S}")
    public void run() {
        outboxRelay.runOnce();
    }
}
