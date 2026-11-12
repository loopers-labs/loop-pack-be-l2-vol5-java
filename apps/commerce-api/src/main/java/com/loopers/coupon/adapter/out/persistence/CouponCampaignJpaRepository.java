package com.loopers.coupon.adapter.out.persistence;

import com.loopers.coupon.domain.CouponCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaign, Long> {

    @Query("SELECT c FROM CouponCampaign c WHERE c.issueStartAt <= :now AND c.issueEndAt > :now AND c.deletedAt IS NULL ORDER BY c.id")
    List<CouponCampaign> findOpenAt(@Param("now") ZonedDateTime now);
}
