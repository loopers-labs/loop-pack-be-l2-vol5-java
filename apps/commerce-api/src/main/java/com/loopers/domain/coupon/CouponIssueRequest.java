package com.loopers.domain.coupon;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 대기열에 접수한 발급 작업 하나. 상태 전이는 Redis Lua가 원자적으로 처리하며,
 * 이 객체는 상태별로 허용된 값 조합과 소유자, 재접수 때의 처리를 정한다.
 */
public class CouponIssueRequest {

    private final String requestId;
    private final Long campaignId;
    private final Long userId;
    private final CouponIssueRequestStatus status;
    private final Instant requestedAt;
    private final Long couponId;
    private final CouponIssueRequestReason reason;
    private final String executionToken;

    private CouponIssueRequest(String requestId, Long campaignId, Long userId, CouponIssueRequestStatus status,
                               Instant requestedAt, Long couponId, CouponIssueRequestReason reason,
                               String executionToken) {
        this.requestId = requestId;
        this.campaignId = campaignId;
        this.userId = userId;
        this.status = status;
        this.requestedAt = requestedAt;
        this.couponId = couponId;
        this.reason = reason;
        this.executionToken = executionToken;
    }

    public static CouponIssueRequest create(Long campaignId, Long userId, Instant requestedAt) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        return new CouponIssueRequest(UUID.randomUUID().toString(), campaignId, userId,
            CouponIssueRequestStatus.WAITING, requestedAt, null, null, null);
    }

    public static CouponIssueRequest reconstitute(String requestId, Long campaignId, Long userId,
                                                  CouponIssueRequestStatus status, Instant requestedAt, Long couponId,
                                                  CouponIssueRequestReason reason, String executionToken) {
        if (requestId == null || requestId.isBlank() || campaignId == null || userId == null
            || status == null || requestedAt == null) {
            throw new IllegalArgumentException("작업 ID, 캠페인, 사용자, 상태, 접수 시각은 필수입니다.");
        }
        validateStatusValues(status, couponId, reason, executionToken);
        return new CouponIssueRequest(requestId, campaignId, userId, status, requestedAt, couponId, reason,
            executionToken);
    }

    private static void validateStatusValues(CouponIssueRequestStatus status, Long couponId,
                                             CouponIssueRequestReason reason, String executionToken) {
        boolean hasToken = executionToken != null && !executionToken.isBlank();
        boolean valid = switch (status) {
            // 재대기할 때 이전 실행 token과 사유를 비우므로 대기 작업에는 남지 않는다.
            case WAITING -> couponId == null && reason == null && executionToken == null;
            case PROCESSING -> couponId == null && reason == null && hasToken;
            case SUCCEEDED -> couponId != null && reason == null;
            case FAILED -> couponId == null && reason != null && reason.getStatus() == CouponIssueRequestStatus.FAILED;
            case UNKNOWN -> couponId == null && reason == CouponIssueRequestReason.DB_RESULT_UNKNOWN && hasToken;
        };
        if (!valid) {
            throw new IllegalArgumentException("작업 상태와 맞지 않는 쿠폰, 사유 또는 실행 token입니다. status=" + status);
        }
    }

    public String getRequestId() { return requestId; }
    public Long getCampaignId() { return campaignId; }
    public Long getUserId() { return userId; }
    public CouponIssueRequestStatus getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Long getCouponId() { return couponId; }
    public CouponIssueRequestReason getReason() { return reason; }
    public String getExecutionToken() { return executionToken; }

    public String getMessage() {
        return reason == null ? null : reason.getMessage();
    }

    public boolean isOwnedBy(Long requesterId) {
        return userId.equals(requesterId);
    }

    /**
     * 같은 사용자와 캠페인이 다시 접수했을 때의 처리. 시스템 실패만 같은 작업 ID로 다시 대기한다.
     */
    public Resubmission resubmission() {
        if (status.isUnfinished()) {
            return Resubmission.RETURN_IN_PROGRESS;
        }
        if (status == CouponIssueRequestStatus.FAILED && reason.isRequeuedOnResubmit()) {
            return Resubmission.REQUEUE;
        }
        return Resubmission.RETURN_RESULT;
    }

    public enum Resubmission {
        RETURN_IN_PROGRESS,
        RETURN_RESULT,
        REQUEUE
    }
}
