package com.loopers.domains.coupon.out;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IssuedCouponJpaRepository extends JpaRepository<IssuedCouponJpaEntity, Long> {
}
