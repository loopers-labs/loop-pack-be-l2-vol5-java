package com.loopers.application.coupon.usecase;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.result.CouponCampaignResult;

public interface PrepareCouponCampaignUseCase {
    CouponCampaignResult execute(CouponCampaignCommand.Create command);

    CouponCampaignResult execute(CouponCampaignCommand.Prepare command);
}
