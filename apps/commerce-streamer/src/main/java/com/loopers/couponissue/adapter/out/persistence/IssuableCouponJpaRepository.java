package com.loopers.couponissue.adapter.out.persistence;

import com.loopers.couponissue.domain.IssuableCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssuableCouponJpaRepository extends JpaRepository<IssuableCoupon, Long> {

    List<IssuableCoupon> findByCampaignIdOrderByIdAsc(Long campaignId);
}
