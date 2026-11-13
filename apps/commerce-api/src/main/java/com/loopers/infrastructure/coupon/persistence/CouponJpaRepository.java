package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.entity.Coupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponJpaRepository extends JpaRepository<Coupon, Long> {
    Page<Coupon> findByUserIdOrderByIssuedAtDescIdDesc(Long userId, Pageable pageable);
}
