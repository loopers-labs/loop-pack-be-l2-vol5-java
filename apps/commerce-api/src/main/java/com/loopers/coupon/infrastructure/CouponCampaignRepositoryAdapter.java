package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.domain.CouponCampaignRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CouponCampaignRepositoryAdapter implements CouponCampaignRepository {

    private final CouponCampaignJpaRepository jpaRepository;

    public CouponCampaignRepositoryAdapter(CouponCampaignJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<CouponCampaign> findById(Long campaignId) {
        return jpaRepository.findById(campaignId);
    }

    @Override
    public Optional<CouponCampaign> findForIssueById(Long campaignId) {
        return jpaRepository.findForIssueById(campaignId);
    }
}
