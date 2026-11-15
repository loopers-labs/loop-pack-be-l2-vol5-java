package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {
    private final CouponCampaignJpaRepository campaigns;

    @Override
    public Optional<CouponCampaignModel> find(Long campaignId) {
        return campaigns.findById(campaignId);
    }

    @Override
    public boolean tryRecordIssue(Long campaignId, Instant now) {
        return campaigns.recordIssue(campaignId, now) == 1;
    }

    @Override
    public boolean tryRecordIssues(Long campaignId, int count, Instant at) {
        return campaigns.recordIssues(campaignId, count, at) == 1;
    }
}
