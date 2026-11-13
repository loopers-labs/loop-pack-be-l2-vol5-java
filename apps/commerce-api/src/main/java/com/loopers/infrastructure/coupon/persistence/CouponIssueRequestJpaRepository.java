package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.entity.CouponIssueRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponIssueRequestJpaRepository extends JpaRepository<CouponIssueRequest, Long> {
    Optional<CouponIssueRequest> findByCampaignIdAndUserId(Long campaignId, Long userId);

    long countByCampaignIdAndPromotedAtIsNotNull(Long campaignId);
}
