package com.loopers.application.coupon.command;

import java.time.Instant;

/** 신뢰하는 로컬 준비 코드의 입력. 고객용 캠페인 CRUD 계약이 아니다. */
public final class CouponCampaignCommand {
    private CouponCampaignCommand() {}

    public record Create(String name, int totalQuota, String benefitDescription, long discountAmount,
                         Instant startsAt, Instant endsAt) {}

    public record Prepare(long campaignId) {}

    public record Activation(long campaignId, boolean active) {}
}
