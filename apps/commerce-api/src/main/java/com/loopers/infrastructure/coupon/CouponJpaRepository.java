package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponJpaRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId);

    // 정렬은 쿼리에 고정한다. idx_coupons_user_issued_id 순서와 같다.
    @Query(
        value = "select c from Coupon c where c.userId = :userId order by c.issuedAt desc, c.id desc",
        countQuery = "select count(c) from Coupon c where c.userId = :userId"
    )
    Page<Coupon> findAllByUserId(@Param("userId") Long userId, Pageable pageable);
}
