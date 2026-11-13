package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaign, Long> {

    @Modifying
    @Query("update CouponCampaign c set c.issuedCount = c.issuedCount + 1 where c.id = :id and c.issuedCount < c.totalQuota")
    int increaseIssuedCount(@Param("id") Long id);
}
