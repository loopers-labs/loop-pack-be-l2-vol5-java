package com.loopers.domain.coupon;

public interface CouponIssueBacklog {

    boolean isDrained(Long campaignId);

    long endOffset(Long campaignId);

    boolean isCommittedUpTo(Long campaignId, long offset);
}
