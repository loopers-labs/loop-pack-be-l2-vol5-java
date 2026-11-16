package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface CouponJpaRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId);

    List<Coupon> findAllByUserIdOrderByIssuedAtDescIdDesc(Long userId);
}
