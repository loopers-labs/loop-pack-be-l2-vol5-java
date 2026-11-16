package com.loopers.domain.coupon;

/** 수량 UPDATE 뒤 읽은 캠페인 scalar 값. 영속 Entity 가 아니라 stale 상태로 flush 될 일이 없다. */
public record CouponCampaignIssueData(Long campaignId, String name, CouponBenefitType benefitType, long benefitValue) {
}
