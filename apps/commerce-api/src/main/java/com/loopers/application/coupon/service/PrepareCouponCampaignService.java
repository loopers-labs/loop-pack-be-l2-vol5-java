package com.loopers.application.coupon.service;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.result.CouponCampaignResult;
import com.loopers.application.coupon.usecase.PrepareCouponCampaignUseCase;
import com.loopers.application.coupon.usecase.SetCouponCampaignActivationUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.coupon.model.CouponBenefit;
import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.model.CouponSlot;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.coupon.repository.CouponSlotRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PrepareCouponCampaignService implements PrepareCouponCampaignUseCase, SetCouponCampaignActivationUseCase {
    private final CouponCampaignRepository campaignRepository;
    private final CouponSlotRepository slotRepository;

    /** 캠페인과 모든 슬롯을 함께 저장한다. 준비만으로 발급을 열지 않는다. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CouponCampaignResult execute(CouponCampaignCommand.Create command) {
        CouponCampaign campaign = campaignRepository.save(CouponCampaign.create(command.name(), command.totalQuota(),
            new CouponBenefit(command.benefitDescription(), command.discountAmount()), command.startsAt(), command.endsAt()));
        return prepare(campaign);
    }

    /** 기존 캠페인의 재준비는 같은 행 잠금으로 직렬화하며 발급 재고를 초기화하지 않는다. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CouponCampaignResult execute(CouponCampaignCommand.Prepare command) {
        return prepare(lockCampaign(command.campaignId()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CouponCampaignResult execute(CouponCampaignCommand.Activation command) {
        CouponCampaign campaign = lockCampaign(command.campaignId());
        long count = slotRepository.countByCampaignId(campaign.getId());
        if (command.active()) {
            campaign.activate(count);
        } else {
            campaign.deactivate();
        }
        return CouponCampaignResult.from(campaignRepository.save(campaign), count);
    }

    private CouponCampaignResult prepare(CouponCampaign campaign) {
        long count = slotRepository.countByCampaignId(campaign.getId());
        if (count == 0 && !campaign.isActive() && campaign.getIssuedCount() == 0) {
            for (int index = 0; index < campaign.getTotalQuota(); index++) {
                slotRepository.save(CouponSlot.create(campaign.getId()));
            }
            count = slotRepository.countByCampaignId(campaign.getId());
        }
        if (count != campaign.getTotalQuota()) {
            throw new DomainException(DomainErrorCode.COUPON_SLOTS_NOT_READY);
        }
        return CouponCampaignResult.from(campaign, count);
    }

    private CouponCampaign lockCampaign(long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.COUPON_CAMPAIGN_NOT_FOUND));
    }
}
