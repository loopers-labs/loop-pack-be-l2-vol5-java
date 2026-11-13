package com.loopers.application.coupon.usecase;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.result.CouponCampaignResult;

public interface SetCouponCampaignActivationUseCase {
    CouponCampaignResult execute(CouponCampaignCommand.Activation command);
}
