package com.loopers.infrastructure.coupon;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select campaign from CouponCampaignJpaEntity campaign where campaign.id = :campaignId")
    Optional<CouponCampaignJpaEntity> lockById(@Param("campaignId") long campaignId);
}
