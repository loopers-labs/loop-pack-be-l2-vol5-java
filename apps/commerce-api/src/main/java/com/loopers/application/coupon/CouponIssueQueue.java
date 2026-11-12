package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;

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

    /**
     * 처리 중인 작업을 성공으로 기록하고 처리 중 List에서 그 작업만 지운다. 실행 token은 남긴다.
     */
    CouponIssueQueueRecord recordSucceeded(String requestId, String executionToken, Long couponId);

    /**
     * 처리 중인 작업을 실패로 기록하고 처리 중 List에서 그 작업만 지운다. 실행 token은 남긴다.
     */
    CouponIssueQueueRecord recordFailed(String requestId, String executionToken, CouponIssueRequestReason reason);

    /**
     * DB 결과를 확인하지 못한 작업을 결과 불명으로 기록하고 처리 중 List에 남긴다.
     */
    CouponIssueQueueRecord recordUnknown(String requestId, String executionToken);

    /**
     * 시스템 실패로 끝난 작업을 같은 작업 ID로 다시 대기시킨다. 작업이 여전히 기대한 사유의 실패이고
     * 미완료 한도가 남았을 때만 바꾸며, 사유와 이전 실행 token을 비운다.
     */
    CouponIssueQueueResubmission requeueFailed(String requestId, CouponIssueRequestReason expectedReason, int capacity);

    /**
     * 시스템 실패로 끝난 작업을 재접수하던 중 DB에서 쿠폰을 확인했을 때, 같은 작업을 성공으로 바꾼다.
     */
    CouponIssueQueueResubmission markFailedSucceeded(String requestId, CouponIssueRequestReason expectedReason,
                                                     Long couponId);
}
