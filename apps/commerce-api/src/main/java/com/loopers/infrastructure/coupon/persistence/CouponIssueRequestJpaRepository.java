package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.entity.CouponIssueRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRequestJpaRepository extends JpaRepository<CouponIssueRequest, Long> {
}
