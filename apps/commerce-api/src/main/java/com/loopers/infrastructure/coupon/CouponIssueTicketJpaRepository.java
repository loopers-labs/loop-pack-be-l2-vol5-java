package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueTicket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueTicketJpaRepository extends JpaRepository<CouponIssueTicket, Long> {
}
