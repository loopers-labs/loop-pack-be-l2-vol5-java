package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.gateway.CouponIssueRequestLog;
import com.loopers.domain.coupon.gateway.CouponIssueStream;
import com.loopers.domain.coupon.repository.CouponIssuanceStore;
import com.loopers.domain.coupon.repository.CouponIssueRequestStore;
import com.loopers.domain.coupon.repository.CouponRebuildLock;
import com.loopers.domain.coupon.vo.IssueRequestRow;
import com.loopers.domain.coupon.vo.RebuildPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 유실 대응 (8주차 D-139, D-140, D-174, D-175). 열린 캠페인마다:
 * 표식이 없으면 DB 에서 Redis 를 다시 만들고(다시 열기는 운영자가 api 의 열기 명령으로), 표식이 있으면 주기적으로 대사한다
 */
@Slf4j
@Component
public class CouponRedisRecovery {

    private final CouponIssuanceStore couponIssuanceStore;
    private final CouponIssueRequestStore couponIssueRequestStore;
    private final CouponIssueStream couponIssueStream;
    private final CouponIssueRequestLog couponIssueRequestLog;
    private final CouponRebuildLock couponRebuildLock;
    private final CouponIssueSteps couponIssueSteps;
    private final Clock clock;
    private final Duration auditInterval;
    private final int auditBatchSize;
    private final Map<Long, Instant> lastAudit = new ConcurrentHashMap<>();

    public CouponRedisRecovery(
        CouponIssuanceStore couponIssuanceStore,
        CouponIssueRequestStore couponIssueRequestStore,
        CouponIssueStream couponIssueStream,
        CouponIssueRequestLog couponIssueRequestLog,
        CouponRebuildLock couponRebuildLock,
        CouponIssueSteps couponIssueSteps,
        Clock clock,
        @Value("${coupon-issue.recovery.audit-interval}") Duration auditInterval,
        @Value("${coupon-issue.recovery.audit-batch-size}") int auditBatchSize
    ) {
        this.couponIssuanceStore = couponIssuanceStore;
        this.couponIssueRequestStore = couponIssueRequestStore;
        this.couponIssueStream = couponIssueStream;
        this.couponIssueRequestLog = couponIssueRequestLog;
        this.couponRebuildLock = couponRebuildLock;
        this.couponIssueSteps = couponIssueSteps;
        this.clock = clock;
        this.auditInterval = auditInterval;
        this.auditBatchSize = auditBatchSize;
    }

    public void runOnce() {
        for (Long campaignId : couponIssuanceStore.findOpenCampaignIds()) {
            try {
                if (couponIssueStream.epochMissing(campaignId)) {
                    rebuild(campaignId);
                } else if (auditDue(campaignId)) {
                    audit(campaignId);
                }
            } catch (RuntimeException e) {
                log.warn("Redis 유실 대응 실패, 다음 회차에 다시 함. campaignId={} cause={}", campaignId, e.toString());
            }
        }
    }

    /** @return 재구성했는가. 잠금을 못 잡았거나 적재가 끝나지 않았거나 이미 다시 열렸으면 false */
    boolean rebuild(long campaignId) {
        if (!couponIssueRequestLog.caughtUp()) {
            log.info("접수 기록 적재가 끝나지 않아 재구성을 미룸. campaignId={}", campaignId);
            return false;
        }
        boolean[] rebuilt = {false};
        boolean locked = couponRebuildLock.runExclusively(campaignId, () -> {
            if (!couponIssueStream.epochMissing(campaignId)) {
                return;
            }
            boolean soldOut = couponIssueSteps.recountIssued(campaignId);
            List<IssueRequestRow> rows = couponIssueRequestStore.findAll(campaignId);
            RebuildPlan plan = RebuildPlan.of(rows, true, soldOut);
            couponIssueStream.rebuild(campaignId, plan);
            rebuilt[0] = true;
            log.warn("Redis 접수 상태를 DB 에서 다시 만들었음. 확인 뒤 api 의 열기 명령으로 다시 연다. campaignId={} rows={} maxSequence={} "
                    + "waiting={} reserve={} promoted={} soldOut={}",
                campaignId, rows.size(), plan.maxSequence(), plan.queue().size(), plan.reserve().size(), plan.promotedCount(), plan.soldOut());
        });
        if (!locked) {
            log.info("다른 인스턴스가 재구성 중이라 건너뜀. campaignId={}", campaignId);
        }
        return rebuilt[0];
    }

    /**
     * 쓰기 순서가 항상 Redis 먼저라, DB 에 접수 기록이 있는데 Redis 사용자 기록이 없으면 Redis 가 잃은 것이다.
     * 하나라도 찾으면 표식을 지워 닫고 다음 회차에 전체 재구성한다 (D-175)
     *
     * @return 유실을 찾았는가
     */
    boolean audit(long campaignId) {
        lastAudit.put(campaignId, clock.instant());
        long afterId = 0;
        while (true) {
            List<long[]> page = couponIssueRequestStore.findUserIdsAfter(campaignId, afterId, auditBatchSize);
            if (page.isEmpty()) {
                return false;
            }
            List<Long> missing = couponIssueStream.missingUsers(campaignId, page.stream().map(row -> row[1]).toList());
            if (!missing.isEmpty()) {
                log.error("Redis 부분 유실을 찾아 접수를 닫고 재구성함. campaignId={} missingUsers={}", campaignId, missing);
                couponIssueStream.dropEpoch(campaignId);
                return true;
            }
            afterId = page.get(page.size() - 1)[0];
        }
    }

    private boolean auditDue(long campaignId) {
        Instant last = lastAudit.get(campaignId);
        return last == null || !clock.instant().isBefore(last.plus(auditInterval));
    }
}
