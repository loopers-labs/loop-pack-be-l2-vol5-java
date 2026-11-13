package com.loopers.infrastructure.persistence.coupon.jpa;

import com.loopers.infrastructure.persistence.coupon.entity.CouponJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponJpaRepository extends JpaRepository<CouponJpaEntity, Long> {
    Optional<CouponJpaEntity> findByCampaignIdAndUserId(long campaignId, long userId);
}
