package com.loopers.coupon.adapter.out.persistence;

import com.loopers.coupon.domain.UserCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserCouponJpaRepository extends JpaRepository<UserCoupon, Long> {

    List<UserCoupon> findByUserIdOrderByIdDesc(Long userId);

    List<UserCoupon> findByCampaignIdAndUserIdOrderByCouponIdAsc(Long campaignId, Long userId);
}
