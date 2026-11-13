package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface CouponJpaRepository extends JpaRepository<CouponJpaEntity, Long> {
    Optional<CouponJpaEntity> findByCampaignIdAndUserId(long campaignId, long userId);

    List<CouponJpaEntity> findByUserIdOrderByIssuedAtDescIdDesc(long userId);

    long countByCampaignId(long campaignId);

    @Transactional
    void deleteByCampaignId(long campaignId);
}
