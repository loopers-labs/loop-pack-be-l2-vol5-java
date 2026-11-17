package com.loopers.couponissue.adapter.out.persistence;

import com.loopers.couponissue.domain.UserCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCouponJpaRepository extends JpaRepository<UserCoupon, Long> {

    boolean existsByCampaignIdAndUserId(Long campaignId, Long userId);
}
