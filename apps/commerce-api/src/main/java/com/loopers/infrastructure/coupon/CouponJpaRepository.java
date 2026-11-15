package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponModel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponJpaRepository extends JpaRepository<CouponModel, Long> {
    Optional<CouponModel> findByCampaignIdAndUserId(Long campaignId, Long userId);

    List<CouponModel> findAllByUserIdOrderByIssuedAtDescIdDesc(Long userId);

    @Query("select c.userId from CouponModel c where c.campaignId = :campaignId")
    List<Long> findUserIdsByCampaignId(@Param("campaignId") Long campaignId);

    List<CouponModel> findAllByCampaignIdAndUserIdIn(Long campaignId, Collection<Long> userIds);
}
