package com.loopers.coupon.adapter.in.scheduler;

import com.loopers.coupon.application.port.in.CouponIssueRelayUseCase;
import com.loopers.coupon.application.port.in.CouponIssueRelayUseCase.RelayResult;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 당첨 기록 relay를 1초마다 돌린다 (W7 relay scheduler와 같은 방식). ShedLock으로 앱 여러 대 중 한 대만 돈다.
 * 꽉 찬 회차는 쉬지 않고 이어 돌되 최대 MAX_DRAIN까지.
 */
@Slf4j
@ConditionalOnProperty(name = "coupon.outbox.relay.scheduler.enabled", havingValue = "true")
@Component
public class CouponIssueRelayScheduler {

    static final Duration MAX_DRAIN = Duration.ofSeconds(30);

    private final CouponIssueRelayUseCase relay;
    private final int batchSize;

    public CouponIssueRelayScheduler(CouponIssueRelayUseCase relay, @Value("${coupon.outbox.relay.batch-size}") int batchSize) {
        this.relay = relay;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${coupon.outbox.relay.tick:1s}", initialDelayString = "${coupon.outbox.relay.tick:1s}")
    @SchedulerLock(name = "coupon-issue-outbox-relay", lockAtMostFor = "5m")
    public void tick() {
        long until = System.nanoTime() + MAX_DRAIN.toNanos();
        RelayResult result;
        do {
            result = relay.relayOnce();
            if (result.selected() > 0) {
                log.info("쿠폰 relay 회차: 고름 {} · PUBLISHED {} · 멈춘 행 {}", result.selected(), result.published(), result.stoppedAt());
            }
        } while (result.selected() == batchSize && !result.stopped() && System.nanoTime() < until);
    }
}
