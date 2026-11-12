package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssuanceModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponIssuanceJpaRepository extends JpaRepository<CouponIssuanceModel, Long> {

    Optional<CouponIssuanceModel> findByCampaignIdAndUserId(Long campaignId, Long userId);
}
