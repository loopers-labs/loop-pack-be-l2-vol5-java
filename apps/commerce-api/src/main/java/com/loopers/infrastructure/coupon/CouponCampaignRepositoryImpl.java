package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {

    private final CouponCampaignJpaRepository jpaRepository;

    @Override
    public CouponCampaign save(CouponCampaign campaign) {
        if (campaign.getId() != null || campaign.getIssuedCount() != 0 || campaign.getTerminatedAt() != null) {
            throw new IllegalArgumentException("발급 전 새 캠페인만 준비 단계에서 저장할 수 있습니다.");
        }
        return CouponCampaignJpaMapper.toDomain(jpaRepository.saveAndFlush(CouponCampaignJpaMapper.toNewEntity(campaign)));
    }

    @Override
    public Optional<CouponCampaign> findById(Long campaignId) {
        return jpaRepository.findByIdAndDeletedAtIsNull(campaignId).map(CouponCampaignJpaMapper::toDomain);
    }

    @Override
    public List<CouponCampaign> findAllByName(String name) {
        return jpaRepository.findAllByNameAndDeletedAtIsNull(name).stream()
            .map(CouponCampaignJpaMapper::toDomain).toList();
    }

    @Override
    public int incrementIssuedCountIfIssuable(Long campaignId, Instant issueNow) {
        return jpaRepository.incrementIssuedCountIfIssuable(campaignId, Objects.requireNonNull(issueNow, "issueNow"));
    }

    @Override
    public int resetSeedState(Long campaignId, Instant startAt, Instant endAt, Instant resetNow) {
        return jpaRepository.resetSeedState(campaignId, Objects.requireNonNull(startAt, "startAt"),
            Objects.requireNonNull(endAt, "endAt"), Objects.requireNonNull(resetNow, "resetNow"));
    }
}
