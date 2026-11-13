package com.loopers.infrastructure.coupon;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface CouponJpaRepository extends JpaRepository<CouponEntity, Long> {

    Optional<CouponEntity> findByCampaignIdAndUserId(Long campaignId, Long userId);

    List<CouponEntity> findByUserIdOrderByIssuedAtDescIdDesc(Long userId);

    @Query("select c.userId from CouponEntity c where c.campaignId = :campaignId and c.userId in :userIds")
    List<Long> userIdsAmong(@Param("campaignId") Long campaignId, @Param("userIds") Collection<Long> userIds);

    List<CouponEntity> findByCampaignIdAndIdGreaterThanOrderByIdAsc(Long campaignId, Long afterId, Pageable page);
}
