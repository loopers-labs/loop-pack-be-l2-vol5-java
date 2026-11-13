package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponIssueTicketRepository {
    CouponIssueTicket save(CouponIssueTicket couponIssueTicket);

    Optional<CouponIssueTicket> findById(Long id);
}
