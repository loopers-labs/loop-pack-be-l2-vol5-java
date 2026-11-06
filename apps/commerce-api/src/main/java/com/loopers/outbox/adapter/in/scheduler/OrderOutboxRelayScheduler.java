package com.loopers.outbox.adapter.in.scheduler;

import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * relay 한 회차의 방아쇠 (docs/week7/plan.md D-1·4단계). 언제 돌릴지만 안다.
 * <p>
 * 겹침 방지 (핵심 4): ShedLock이 작업 이름 {@value #LOCK_NAME} 한 행을 잠근다. 잠금을 얻은 실행만 relayOnce() 한 번 전체를 돌리고,
 * 못 얻은 실행(같은 앱의 다른 호출, 다른 앱의 tick)은 기다리지 않고 건너뛴다. 끝나면 바로 풀고(lockAtLeastFor 0),
 * 쥔 채 죽으면 lockAtMostFor 5분 뒤 풀린다 — 한 회차의 최악(성공 몇 초 + 실패 대기 35초)보다 넉넉하다.
 * fixedDelay라 한 앱 안에서도 앞 tick이 끝나야 다음 tick을 센다. 한 tick 안에서 꽉 찬 회차는 쉬지 않고 이어 돈다(최대 {@link #MAX_DRAIN}).
 * outbox.relay.scheduler.enabled=false(test 프로필)면 빈을 만들지 않는다 — 테스트는 relay를 직접 부른다.
 */
@Slf4j
@ConditionalOnProperty(name = "outbox.relay.scheduler.enabled", havingValue = "true")
@Component
public class OrderOutboxRelayScheduler {

    public static final String LOCK_NAME = "order-outbox-relay";

    /**
     * 밀린 행을 이어서 비우는 최대 시간. 회차가 꽉 차면(고른 수 = 묶음 크기) 1초를 쉬지 않고 바로 다음 회차를 돈다.
     * lockAtMostFor(5분)보다 한참 짧게 끊어, 잠금 기한 안에 tick을 끝낸다. 끊긴 뒤에도 남은 행은 다음 tick이 이어 보낸다.
     */
    static final Duration MAX_DRAIN = Duration.ofSeconds(30);

    private final OrderOutboxRelayUseCase relay;
    private final int batchSize;

    public OrderOutboxRelayScheduler(OrderOutboxRelayUseCase relay, @Value("${outbox.relay.batch-size}") int batchSize) {
        this.relay = relay;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.tick:1s}", initialDelayString = "${outbox.relay.tick:1s}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "5m")
    public void tick() {
        long until = System.nanoTime() + MAX_DRAIN.toNanos();
        RelayResult result;
        do {
            result = relay.relayOnce();
            if (result.selected() > 0) {
                log.info("relay 회차: 고름 {} · PUBLISHED {} · 멈춘 행 {}", result.selected(), result.published(), result.stoppedAt());
            }
        } while (result.selected() == batchSize && !result.stopped() && System.nanoTime() < until);
    }
}
