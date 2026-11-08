package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface CouponJpaRepository extends JpaRepository<CouponEntity, Long> {

    Optional<CouponEntity> findByCampaignIdAndUserId(Long campaignId, Long userId);

    List<CouponEntity> findByUserIdOrderByIssuedAtDescIdDesc(Long userId);
}
