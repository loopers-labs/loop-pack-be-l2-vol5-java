package com.loopers.domains.coupon.out;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignJpaEntity, Long> {
    // 캠페인 한 행과, 그 사용자의 쿠폰이 있으면 그 쿠폰(없으면 null)을 한 번에 읽는다. 잠금은 없다.
    @Query("select c, i from CouponCampaignJpaEntity c left join IssuedCouponJpaEntity i"
        + " on i.campaignId = c.id and i.userId = :userId where c.id = :campaignId")
    List<Object[]> findWithUserCoupon(@Param("campaignId") long campaignId, @Param("userId") long userId);

    // 조건을 만족할 때만 1행이 바뀐다. MySQL이 같은 행의 UPDATE를 직렬화하므로 issued_count가 total_quota를 넘지 않는다(D125).
    // UPDATE 뒤 영속성 컨텍스트의 캠페인 값은 낡으므로 비운다. 혜택은 그 뒤에 다시 읽는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CouponCampaignJpaEntity c set c.issuedCount = c.issuedCount + 1"
        + " where c.id = :campaignId and c.active = true and c.issuedCount < c.totalQuota")
    int increaseIssued(@Param("campaignId") long campaignId);
}
