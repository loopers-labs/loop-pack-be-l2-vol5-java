package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponIssueDeadLetterService;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CouponTransactions {

    private final CouponService couponService;
    private final CouponIssueDeadLetterService deadLetterService;

    @Transactional
    public Coupon issue(Long campaignId, Long userId, int issueNumber, Instant reservedAt) {
        return couponService.issue(campaignId, userId, issueNumber, reservedAt);
    }

    @Transactional
    public List<Coupon> issueAll(Long campaignId, List<CouponIssue> issues) {
        return couponService.issueAll(campaignId, issues);
    }

    @Transactional
    public boolean markDeadLetterReplayed(Long deadLetterId, Instant replayedAt) {
        return deadLetterService.markReplayed(deadLetterId, replayedAt);
    }
}
