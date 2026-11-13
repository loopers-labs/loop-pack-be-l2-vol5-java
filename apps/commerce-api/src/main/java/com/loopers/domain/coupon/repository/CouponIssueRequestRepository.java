package com.loopers.domain.coupon.repository;

import com.loopers.domain.coupon.entity.CouponIssueRequest;

import java.util.Optional;

/** Redis 장애 때 결과 조회의 원본 (8주차 D-145) */
public interface CouponIssueRequestRepository {
    Optional<CouponIssueRequest> find(Long campaignId, Long userId);

    long countPromoted(Long campaignId);
}
