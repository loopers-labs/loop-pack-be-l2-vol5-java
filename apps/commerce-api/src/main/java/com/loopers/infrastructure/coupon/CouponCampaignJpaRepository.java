package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignModel, Long> {

    @Modifying
    @Query("UPDATE CouponCampaignModel c SET c.issuedCount = c.issuedCount + 1 "
        + "WHERE c.id = :id AND c.issuedCount < c.totalQuota")
    int incrementIssuedCountIfAvailable(@Param("id") long id);
}
