package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaign, Long> {
}
