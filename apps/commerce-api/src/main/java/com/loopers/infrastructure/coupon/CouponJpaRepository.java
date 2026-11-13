package com.loopers.infrastructure.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface CouponJpaRepository extends JpaRepository<CouponEntity, Long> {

    Optional<CouponEntity> findByCampaignIdAndUserId(Long campaignId, Long userId);

    List<CouponEntity> findByUserIdOrderByIssuedAtDescIdDesc(Long userId);

    @Query("select coalesce(max(c.issueNumber), 0) from CouponEntity c where c.campaignId = :campaignId")
    int maxIssueNumber(@Param("campaignId") Long campaignId);

    @Query("select c.issueNumber from CouponEntity c "
        + "where c.campaignId = :campaignId and c.issueNumber between :from and :to")
    List<Integer> issueNumbersBetween(@Param("campaignId") Long campaignId, @Param("from") int from, @Param("to") int to);
}
