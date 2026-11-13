package com.loopers.application.coupon.command;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

public record CouponIssueCommand(long campaignId, long userId) {
    public CouponIssueCommand {
        if (campaignId <= 0) {
            throw new IllegalArgumentException("캠페인 ID는 양수여야 합니다.");
        }
        if (userId <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_USER_ID);
        }
    }
}
