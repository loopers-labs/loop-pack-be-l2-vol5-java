package com.loopers.coupon.application.port.in;

import com.loopers.coupon.domain.CouponDraft;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 관리자가 쿠폰 캠페인을 만드는 입구 (D-7). 캠페인과 쿠폰들을 한 트랜잭션에서 만든다(쿠폰팩, D-5).
 */
public interface CouponCampaignCommandUseCase {

    CouponCampaignAdminInfo create(CreateCampaignCommand command);

    record CreateCampaignCommand(
        String name,
        int totalQuantity,
        Integer queueCapacity,
        ZonedDateTime issueStartAt,
        ZonedDateTime issueEndAt,
        List<CouponDraft> coupons
    ) {}
}
