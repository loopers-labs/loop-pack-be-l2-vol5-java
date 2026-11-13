package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignEntity, Long> {

    @Query("select c.id from CouponCampaignEntity c where c.active = true")
    List<Long> findActiveIds();

    @Modifying
    @Query(value = """
        UPDATE coupon_campaign
           SET issued_count = issued_count + 1, updated_at = NOW(6)
         WHERE id = :id AND issued_count < total_quota
        """, nativeQuery = true)
    int increaseIssuedCountWithinQuota(@Param("id") Long id);
}
