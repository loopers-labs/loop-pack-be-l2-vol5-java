package com.loopers.outbox.adapter.in.scheduler;

import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * relay 한 회차의 방아쇠 (docs/week7/plan.md D-1·4단계). 언제 돌릴지만 안다.
 * <p>
 * 겹침 방지 (핵심 4): ShedLock이 작업 이름 {@value #LOCK_NAME} 한 행을 잠근다. 잠금을 얻은 실행만 relayOnce() 한 번 전체를 돌리고,
 * 못 얻은 실행(같은 앱의 다른 호출, 다른 앱의 tick)은 기다리지 않고 건너뛴다. 끝나면 바로 풀고(lockAtLeastFor 0),
 * 쥔 채 죽으면 lockAtMostFor 5분 뒤 풀린다 — 한 회차의 최악(성공 몇 초 + 실패 대기 35초)보다 넉넉하다.
 * fixedDelay라 한 앱 안에서도 앞 회차가 끝나야 다음 tick을 센다.
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
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "5m")
    public void tick() {
        RelayResult result = relay.relayOnce();
        if (result.selected() > 0) {
            log.info("relay 회차: 고름 {} · PUBLISHED {} · 멈춘 행 {}", result.selected(), result.published(), result.stoppedAt());
        }
    }
}
