package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponCampaign;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaign, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select campaign from CouponCampaign campaign where campaign.id = :campaignId")
    Optional<CouponCampaign> findForIssueById(@Param("campaignId") Long campaignId);
}
