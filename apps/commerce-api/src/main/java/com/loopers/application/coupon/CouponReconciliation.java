package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponIssuePublisher;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.CouponReservation.Reservation;
import com.loopers.domain.coupon.CouponService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponReconciliation {

    static final String REPUBLISHED = "coupon.issue.republished";
    static final String BACKLOG = "coupon.issue.backlog";
    private static final int PAGE = 1_000;

    private final CouponReservation couponReservation;
    private final CouponService couponService;
    private final CouponIssuePublisher couponIssuePublisher;
    private final CampaignCache campaignCache;
    private final MeterRegistry meterRegistry;
    private final Map<Long, Watermark> watermarks = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> backlogs = new ConcurrentHashMap<>();

    record Watermark(int seenMax, int confirmedUpTo) {
    }

    public void reconcileAll() {
        for (Long campaignId : campaignCache.campaignIds()) {
            try {
                reconcile(campaignId);
            } catch (RuntimeException e) {
                log.warn("대사가 실패해 다음 주기에 다시 본다: campaignId={}", campaignId, e);
            }
        }
    }

    public int reconcile(Long campaignId) {
        Watermark previous = watermarks.getOrDefault(campaignId, new Watermark(0, 0));
        int dbMax = couponService.maxIssueNumber(campaignId);
        int bound = Math.min(previous.seenMax(), dbMax);
        int confirmedUpTo = previous.confirmedUpTo();
        boolean prefixIntact = true;
        int republished = 0;
        int cursor = previous.confirmedUpTo();
        while (cursor < bound) {
            List<Reservation> page = couponReservation.reservedBetween(campaignId, cursor, bound, PAGE);
            if (page.isEmpty()) {
                break;
            }
            Set<Integer> issued = couponService.issuedNumbersBetween(campaignId,
                page.get(0).issueNumber(), page.get(page.size() - 1).issueNumber());
            for (Reservation reservation : page) {
                if (issued.contains(reservation.issueNumber())) {
                    if (prefixIntact) {
                        confirmedUpTo = reservation.issueNumber();
                    }
                    continue;
                }
                prefixIntact = false;
                if (republish(campaignId, reservation)) {
                    republished++;
                }
            }
            cursor = page.get(page.size() - 1).issueNumber();
            if (page.size() < PAGE) {
                break;
            }
        }
        watermarks.put(campaignId, new Watermark(couponReservation.largestNumber(campaignId), confirmedUpTo));
        int dbIssuedCount = couponService.findCampaign(campaignId).map(CouponCampaign::getIssuedCount).orElse(0);
        campaignCache.refreshIssuedCount(campaignId, dbIssuedCount);
        backlog(campaignId).set(couponReservation.count(campaignId) - dbIssuedCount);
        return republished;
    }

    private boolean republish(Long campaignId, Reservation reservation) {
        CouponIssuePublisher.Outcome outcome = couponIssuePublisher.publish(
            new CouponIssue(campaignId, reservation.userId(), reservation.issueNumber(), Instant.now()));
        if (!(outcome instanceof CouponIssuePublisher.Outcome.Published)) {
            log.warn("누락된 접수를 다시 보내지 못했다. 다음 주기에 다시 본다: campaignId={}, userId={}, issueNumber={}, outcome={}",
                campaignId, reservation.userId(), reservation.issueNumber(), outcome);
            return false;
        }
        meterRegistry.counter(REPUBLISHED, "campaignId", String.valueOf(campaignId)).increment();
        log.warn("DB 에 없는 접수를 다시 보냈다: campaignId={}, userId={}, issueNumber={}",
            campaignId, reservation.userId(), reservation.issueNumber());
        return true;
    }

    public void clear() {
        watermarks.clear();
    }

    private AtomicLong backlog(Long campaignId) {
        return backlogs.computeIfAbsent(campaignId,
            id -> meterRegistry.gauge(BACKLOG, Tags.of("campaignId", String.valueOf(id)), new AtomicLong()));
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "coupon.issue.reconcile.enabled", havingValue = "true")
    @RequiredArgsConstructor
    static class Schedule {

        private final CouponReconciliation reconciliation;

        @Scheduled(fixedDelayString = "${coupon.issue.reconcile.interval:10s}")
        void reconcile() {
            reconciliation.reconcileAll();
        }
    }
}
