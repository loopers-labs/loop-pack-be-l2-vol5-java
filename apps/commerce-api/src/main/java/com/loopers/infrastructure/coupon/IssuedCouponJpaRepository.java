package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.IssuedCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IssuedCouponJpaRepository extends JpaRepository<IssuedCoupon, Long> {

    Optional<IssuedCoupon> findByCampaignIdAndUserId(Long campaignId, Long userId);

    // idx(user_id, issued_at, id)를 타는 경로다(docs/week8/decisions.md D-16).
    List<IssuedCoupon> findAllByUserIdOrderByIssuedAtDescIdDesc(Long userId);
}
