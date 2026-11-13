package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignModel, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CouponCampaignModel c
           set c.issuedCount = c.issuedCount + 1, c.updatedAt = :now
         where c.id = :id and c.active = true and c.issuedCount < c.totalQuota
        """)
    int increaseIssuedCount(@Param("id") Long id, @Param("now") ZonedDateTime now);
}
