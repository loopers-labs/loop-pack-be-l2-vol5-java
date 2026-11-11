package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignJpaEntity, Long> {

    Optional<CouponCampaignJpaEntity> findByIdAndDeletedAtIsNull(Long campaignId);

    List<CouponCampaignJpaEntity> findAllByNameAndDeletedAtIsNull(String name);

    // MySQL은 SET을 왼쪽부터 평가한다. 종료 시각은 증가 전 수량으로 먼저 계산한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE coupon_campaigns
        SET terminated_at = CASE
                WHEN issued_count = total_quota - 1 THEN :issueNow
                ELSE terminated_at
            END,
            issued_count = issued_count + 1,
            updated_at = :issueNow
        WHERE id = :campaignId
          AND deleted_at IS NULL
          AND start_at <= :issueNow
          AND :issueNow < end_at
          AND terminated_at IS NULL
          AND issued_count < total_quota
        """, nativeQuery = true)
    int incrementIssuedCountIfIssuable(@Param("campaignId") Long campaignId, @Param("issueNow") Instant issueNow);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE coupon_campaigns
        SET issued_count = 0, terminated_at = NULL,
            start_at = :startAt, end_at = :endAt, updated_at = :resetNow
        WHERE id = :campaignId AND deleted_at IS NULL
        """, nativeQuery = true)
    int resetSeedState(@Param("campaignId") Long campaignId, @Param("startAt") Instant startAt,
                       @Param("endAt") Instant endAt, @Param("resetNow") Instant resetNow);
}
