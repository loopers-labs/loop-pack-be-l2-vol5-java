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

    /**
     * 대기 List에 작업이 있는지 확인한다. worker는 permit을 얻기 전에 이 값으로 가져올 작업이 있는지 본다.
     */
    boolean hasWaiting();

    /**
     * 대기 List 맨 앞의 작업을 처리 중 List로 옮기고, 작업을 처리 중으로 바꾸며 실행 token을 기록한다.
     * permit을 이미 얻은 실행만 호출한다.
     */
    CouponIssueQueueClaim claim(String executionToken);
}
