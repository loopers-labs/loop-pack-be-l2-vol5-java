package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueTicket;
import com.loopers.domain.coupon.CouponIssueTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponIssueTicketRepositoryImpl implements CouponIssueTicketRepository {
    private final CouponIssueTicketJpaRepository couponIssueTicketJpaRepository;

    @Override
    public CouponIssueTicket save(CouponIssueTicket couponIssueTicket) {
        return couponIssueTicketJpaRepository.save(couponIssueTicket);
    }

    @Override
    public CouponIssueTicket insert(CouponIssueTicket couponIssueTicket) {
        return couponIssueTicketJpaRepository.saveAndFlush(couponIssueTicket);
    }

    @Override
    public Optional<CouponIssueTicket> findById(Long id) {
        return couponIssueTicketJpaRepository.findById(id);
    }

    @Override
    public List<CouponIssueTicket> findAllByCampaignId(Long campaignId) {
        return couponIssueTicketJpaRepository.findAllByCampaignId(campaignId);
    }
}
