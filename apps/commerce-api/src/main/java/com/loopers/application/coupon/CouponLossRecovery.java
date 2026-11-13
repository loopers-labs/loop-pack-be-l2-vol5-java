package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueBacklog;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.CouponReservation.Reservation;
import com.loopers.domain.coupon.CouponService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class CouponLossRecovery {

    static final String RECOVERED = "coupon.issue.recovery";
    private static final int PAGE = 1_000;
    private static final Duration DRAIN_POLL = Duration.ofMillis(500);
    private static final int DRAIN_LOG_EVERY = 20;

    private final CouponReservation couponReservation;
    private final CouponService couponService;
    private final CouponIssueBacklog backlog;
    private final MeterRegistry meterRegistry;
    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "coupon-loss-recovery");
        thread.setDaemon(true);
        return thread;
    });

    public CouponLossRecovery(CouponReservation couponReservation, CouponService couponService,
                              CouponIssueBacklog backlog, MeterRegistry meterRegistry) {
        this.couponReservation = couponReservation;
        this.couponService = couponService;
        this.backlog = backlog;
        this.meterRegistry = meterRegistry;
    }

    public void start(Long campaignId) {
        couponReservation.close(campaignId);
        if (!inProgress.add(campaignId)) {
            return;
        }
        log.error("Redis 예약 집합이 DB 와 어긋나 캠페인을 닫고 복구를 시작한다: campaignId={}", campaignId);
        executor.execute(() -> {
            try {
                recover(campaignId);
            } catch (RuntimeException e) {
                log.error("복구가 실패했다. 캠페인은 닫힌 채다. 다시 시작하려면 유실 감지가 다시 울리거나 수동 복구가 필요하다: campaignId={}",
                    campaignId, e);
            } finally {
                inProgress.remove(campaignId);
            }
        });
    }

    public void recover(Long campaignId) {
        couponReservation.close(campaignId);
        awaitDrained(campaignId);
        int restored = rebuildFromDb(campaignId);
        couponReservation.open(campaignId);
        meterRegistry.counter(RECOVERED, "campaignId", String.valueOf(campaignId)).increment();
        log.warn("DB 쿠폰으로 예약 집합을 다시 만들고 캠페인을 열었다: campaignId={}, restored={}", campaignId, restored);
    }

    private void awaitDrained(Long campaignId) {
        int polls = 0;
        while (!backlog.isDrained()) {
            polls++;
            if (polls % DRAIN_LOG_EVERY == 0) {
                log.warn("워커가 broker 를 비우기를 기다린다: campaignId={}, waited={}", campaignId, DRAIN_POLL.multipliedBy(polls));
            }
            try {
                Thread.sleep(DRAIN_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("복구 중 중단됐다: campaignId=" + campaignId, e);
            }
        }
    }

    private int rebuildFromDb(Long campaignId) {
        couponReservation.clear(campaignId);
        int restored = 0;
        int after = 0;
        while (true) {
            List<Reservation> page = couponService.issuedAfter(campaignId, after, PAGE);
            if (page.isEmpty()) {
                return restored;
            }
            couponReservation.restore(campaignId, page);
            restored += page.size();
            after = page.get(page.size() - 1).issueNumber();
            if (page.size() < PAGE) {
                return restored;
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
