package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface CouponIssueTicketRepository {
    CouponIssueTicket save(CouponIssueTicket couponIssueTicket);

    /** 저장하고 즉시 DB 에 반영한다. 유일 제약 충돌을 이 호출에서 드러낸다. */
    CouponIssueTicket insert(CouponIssueTicket couponIssueTicket);

    Optional<CouponIssueTicket> findById(Long id);

    List<CouponIssueTicket> findAllByCampaignId(Long campaignId);
}
