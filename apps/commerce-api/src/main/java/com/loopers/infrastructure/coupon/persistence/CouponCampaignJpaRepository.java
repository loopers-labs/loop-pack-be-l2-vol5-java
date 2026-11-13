package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.entity.CouponCampaign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaign, Long> {
}
