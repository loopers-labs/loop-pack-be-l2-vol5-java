package com.loopers.domain.coupon.vo;

/** 접수 topic 에서 읽은 접수 사실. 같은 (캠페인, 사용자)는 한 번만 적재한다 (8주차 D-132, D-134) */
public record CouponIssueRequested(long campaignId, long userId, long sequence) {

    public CouponIssueRequested {
        if (campaignId < 1 || userId < 1 || sequence < 1) {
            throw new IllegalArgumentException(
                "접수 사실의 식별자 · 순번은 1 이상. campaignId=" + campaignId + " userId=" + userId + " sequence=" + sequence);
        }
    }
}
