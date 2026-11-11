package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponJpaRepository extends JpaRepository<CouponJpaEntity, Long> {

    Optional<CouponJpaEntity> findByIdAndDeletedAtIsNull(Long couponId);

    @Query(value = """
        SELECT id FROM coupons
        WHERE campaign_id = :campaignId AND user_id IS NULL AND issued_at IS NULL AND deleted_at IS NULL
        ORDER BY id LIMIT 1
        """, nativeQuery = true)
    Optional<Long> findFirstUnissuedId(@Param("campaignId") Long campaignId);

    Optional<CouponJpaEntity> findByCampaignIdAndUserIdAndIssuedAtIsNotNullAndDeletedAtIsNull(Long campaignId, Long userId);

    List<CouponJpaEntity> findAllByUserIdAndIssuedAtIsNotNullAndDeletedAtIsNullOrderByIssuedAtDescIdDesc(Long userId);

    long countByCampaignIdAndDeletedAtIsNull(Long campaignId);

    long countByCampaignIdAndUserIdIsNotNullAndIssuedAtIsNotNullAndDeletedAtIsNull(Long campaignId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE coupons
        SET user_id = :userId, issued_at = :issueNow, updated_at = :issueNow
        WHERE id = :couponId AND campaign_id = :campaignId
          AND user_id IS NULL AND issued_at IS NULL AND deleted_at IS NULL
        """, nativeQuery = true)
    int assignIfUnissued(@Param("couponId") Long couponId, @Param("campaignId") Long campaignId,
                         @Param("userId") Long userId, @Param("issueNow") Instant issueNow);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE coupons SET user_id = NULL, issued_at = NULL, updated_at = :resetNow
        WHERE campaign_id = :campaignId AND deleted_at IS NULL
        """, nativeQuery = true)
    int resetSeedAssignments(@Param("campaignId") Long campaignId, @Param("resetNow") Instant resetNow);
}
