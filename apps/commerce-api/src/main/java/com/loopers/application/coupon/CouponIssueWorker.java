package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueWorker {

    private final CouponTransactions couponTransactions;

    public void record(CouponIssue issue) {
        Coupon coupon = couponTransactions.issue(issue.campaignId(), issue.userId(), issue.issueNumber(),
            issue.reservedAt());
        log.debug("접수를 쿠폰으로 기록했다: campaignId={}, userId={}, issueNumber={}, couponId={}",
            issue.campaignId(), issue.userId(), issue.issueNumber(), coupon.id());
    }
}
