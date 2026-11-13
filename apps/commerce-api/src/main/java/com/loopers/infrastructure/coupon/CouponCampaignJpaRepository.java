package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaign, Long> {

    // 마지막 한 장을 지키는 락이다(docs/week8/decisions.md D-8). 캠페인 행을 바꾸는 모든 경로가 이 조회를 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CouponCampaign c WHERE c.id = :id")
    Optional<CouponCampaign> findForUpdate(@Param("id") Long id);
}
