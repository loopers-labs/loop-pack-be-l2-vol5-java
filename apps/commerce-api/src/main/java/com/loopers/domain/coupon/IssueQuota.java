package com.loopers.domain.coupon;

public record IssueQuota(int value) {

    public IssueQuota {
        if (value <= 0) {
            throw new IllegalArgumentException("총 발급 한도는 양의 정수여야 합니다: " + value);
        }
    }

    public static IssueQuota of(int value) {
        return new IssueQuota(value);
    }
}
