package com.loopers.infrastructure.promotion;

import com.loopers.domain.promotion.IssuedCouponModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssuedCouponJpaRepository extends JpaRepository<IssuedCouponModel, Long> {
}
