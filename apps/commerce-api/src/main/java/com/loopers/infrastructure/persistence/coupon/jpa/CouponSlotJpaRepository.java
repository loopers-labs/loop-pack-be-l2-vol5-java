package com.loopers.infrastructure.persistence.coupon.jpa;

import com.loopers.infrastructure.persistence.coupon.entity.CouponSlotJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponSlotJpaRepository extends JpaRepository<CouponSlotJpaEntity, Long> {
    long countByCampaignId(long campaignId);

    @Query(value = "SELECT * FROM coupon_slots WHERE campaign_id = :campaignId AND state = 'AVAILABLE'"
        + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<CouponSlotJpaEntity> findAvailableForUpdateSkipLocked(@Param("campaignId") long campaignId);
}
