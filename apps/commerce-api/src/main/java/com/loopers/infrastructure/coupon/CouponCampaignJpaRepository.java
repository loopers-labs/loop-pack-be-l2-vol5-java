package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignModel, Long> {

    /** 활성·잔여 수량 확인과 증가를 한 SQL 로 한다. 쓰기 TX 의 첫 SQL 이라 flush 할 변경이 없어 flushAutomatically 를 켜지 않는다. */
    @Modifying
    @Query(value = """
        UPDATE coupon_campaign
           SET issued_count = issued_count + 1,
               updated_at = :now
         WHERE id = :campaignId
           AND status = 'ACTIVE'
           AND issued_count < total_quota
        """, nativeQuery = true)
    int reserveOne(@Param("campaignId") Long campaignId, @Param("now") ZonedDateTime now);

    @Query("""
        select new com.loopers.domain.coupon.CouponCampaignIssueData(c.id, c.name, c.benefitType, c.benefitValue)
          from CouponCampaignModel c
         where c.id = :campaignId
        """)
    Optional<CouponCampaignIssueData> findIssueData(@Param("campaignId") Long campaignId);
}
