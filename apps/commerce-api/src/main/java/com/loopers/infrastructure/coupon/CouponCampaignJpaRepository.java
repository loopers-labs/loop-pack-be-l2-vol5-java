package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignModel, Long> {
    @Modifying
    @Query("update CouponCampaignModel c set c.issuedCount = c.issuedCount + 1 "
        + "where c.id = :id and c.active = true and c.endAt > :now and c.issuedCount < c.totalQuota")
    int recordIssue(@Param("id") Long campaignId, @Param("now") Instant now);

    @Modifying
    @Query("update CouponCampaignModel c set c.issuedCount = c.issuedCount + :count "
        + "where c.id = :id and c.active = true and c.endAt > :at and c.issuedCount + :count <= c.totalQuota")
    int recordIssues(@Param("id") Long campaignId, @Param("count") int count, @Param("at") Instant at);
}
