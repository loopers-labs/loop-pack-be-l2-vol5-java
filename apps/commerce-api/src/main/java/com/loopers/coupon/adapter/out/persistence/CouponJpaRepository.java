package com.loopers.coupon.adapter.out.persistence;

import com.loopers.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponJpaRepository extends JpaRepository<Coupon, Long> {

    List<Coupon> findByCampaignIdOrderByIdAsc(Long campaignId);
}
