package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.entity.CouponIssueRequest;
import com.loopers.domain.coupon.repository.CouponIssueRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponIssueRequestRepositoryImpl implements CouponIssueRequestRepository {

    private final CouponIssueRequestJpaRepository couponIssueRequestJpaRepository;

    @Override
    public Optional<CouponIssueRequest> find(Long campaignId, Long userId) {
        return couponIssueRequestJpaRepository.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public long countPromoted(Long campaignId) {
        return couponIssueRequestJpaRepository.countByCampaignIdAndPromotedAtIsNotNull(campaignId);
    }
}
