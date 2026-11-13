package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.support.error.DomainException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueWorker {

    static final String REJECTED_BY_DB = "coupon.issue.rejected_by_db";

    private final CouponTransactions couponTransactions;
    private final CouponLossRecovery couponLossRecovery;
    private final MeterRegistry meterRegistry;

    public void record(CouponIssue issue) {
        try {
            Coupon coupon = couponTransactions.issue(issue.campaignId(), issue.userId(), issue.issueNumber(),
                issue.reservedAt());
            log.debug("접수를 쿠폰으로 기록했다: campaignId={}, userId={}, issueNumber={}, couponId={}",
                issue.campaignId(), issue.userId(), issue.issueNumber(), coupon.id());
        } catch (DomainException e) {
            switch (e.error()) {
                case COUPON_ALREADY_ISSUED -> log.info("이미 기록된 접수라 넘긴다: campaignId={}, userId={}, issueNumber={}",
                    issue.campaignId(), issue.userId(), issue.issueNumber());
                case COUPON_SOLD_OUT -> rejectedByDb(issue);
                default -> throw e;
            }
        }
    }

    private void rejectedByDb(CouponIssue issue) {
        meterRegistry.counter(REJECTED_BY_DB, "campaignId", String.valueOf(issue.campaignId())).increment();
        log.warn("Redis 가 받아들인 접수를 DB 가 품절로 거절했다. 예약 집합과 DB 가 어긋났다: campaignId={}, userId={}, issueNumber={}",
            issue.campaignId(), issue.userId(), issue.issueNumber());
        couponLossRecovery.start(issue.campaignId());
    }
}
