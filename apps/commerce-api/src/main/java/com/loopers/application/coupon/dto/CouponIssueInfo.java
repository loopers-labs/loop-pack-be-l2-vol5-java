package com.loopers.application.coupon.dto;

import com.loopers.domain.coupon.enums.IssueState;
import com.loopers.domain.coupon.vo.IssueSnapshot;

/** POST 와 결과 조회가 같은 모양을 쓴다 (8주차 D-144, D-149) */
public record CouponIssueInfo(Long campaignId, IssueState state, Integer reservePosition, Long couponId) {

    public static CouponIssueInfo from(Long campaignId, IssueSnapshot snapshot) {
        return new CouponIssueInfo(campaignId, snapshot.state(), snapshot.reservePosition(), snapshot.couponId());
    }

    public static CouponIssueInfo soldOut(Long campaignId) {
        return new CouponIssueInfo(campaignId, IssueState.SOLD_OUT, null, null);
    }
}
