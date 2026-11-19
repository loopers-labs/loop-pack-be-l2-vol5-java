package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponIssueTicketJpaRepository extends JpaRepository<CouponIssueTicket, Long> {
    List<CouponIssueTicket> findAllByCampaignId(Long campaignId);
}
