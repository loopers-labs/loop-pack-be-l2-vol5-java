package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequest;

import java.util.Optional;

/**
 * 쿠폰 발급 대기열 저장소. 상태 변경은 저장소가 원자적으로 처리한다.
 */
public interface CouponIssueQueue {

    /**
     * 같은 사용자와 캠페인의 기존 작업을 먼저 확인하고, 없으면 미완료 한도 안에서 새 작업을 대기열 끝에 추가한다.
     */
    CouponIssueQueueSubmission submit(CouponIssueRequest request, int capacity);

    Optional<CouponIssueRequest> findById(String requestId);

    Optional<CouponIssueRequest> findByCampaignIdAndUserId(Long campaignId, Long userId);
}
