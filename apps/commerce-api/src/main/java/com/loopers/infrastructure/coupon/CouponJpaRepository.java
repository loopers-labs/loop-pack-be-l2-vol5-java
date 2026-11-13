package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CouponJpaRepository extends JpaRepository<CouponJpaEntity, Long> {

    Optional<CouponJpaEntity> findByIdAndDeletedAtIsNull(Long couponId);

    Optional<CouponJpaEntity> findByCampaignIdAndUserIdAndIssuedAtIsNotNullAndDeletedAtIsNull(Long campaignId, Long userId);

    List<CouponJpaEntity> findAllByUserIdAndIssuedAtIsNotNullAndDeletedAtIsNullOrderByIssuedAtDescIdDesc(Long userId);

    long countByCampaignIdAndUserIdIsNotNullAndIssuedAtIsNotNullAndDeletedAtIsNull(Long campaignId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM coupons WHERE campaign_id = :campaignId", nativeQuery = true)
    int deleteByCampaignId(@Param("campaignId") Long campaignId);
}
