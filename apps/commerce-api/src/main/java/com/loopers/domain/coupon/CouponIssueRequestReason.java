package com.loopers.domain.coupon;

/**
 * 발급되지 않았거나 결과를 확인하지 못한 작업의 원인. 작업 응답의 사유 문구도 이 값으로 정한다.
 */
public enum CouponIssueRequestReason {
    SOLD_OUT(CouponIssueRequestStatus.FAILED, false, "쿠폰이 모두 발급된 캠페인입니다."),
    CAMPAIGN_NOT_ISSUABLE(CouponIssueRequestStatus.FAILED, false, "쿠폰 발급 기간이 아닙니다."),
    CAMPAIGN_NOT_FOUND(CouponIssueRequestStatus.FAILED, false, "쿠폰 캠페인을 찾을 수 없습니다."),
    DB_ERROR(CouponIssueRequestStatus.FAILED, true, "일시적인 DB 오류로 발급하지 못했습니다. 다시 접수할 수 있습니다."),
    INTERNAL_ERROR(CouponIssueRequestStatus.FAILED, true, "처리 중 오류로 발급하지 못했습니다. 다시 접수할 수 있습니다."),
    DB_RESULT_UNKNOWN(CouponIssueRequestStatus.UNKNOWN, false, "발급 결과를 확인하고 있습니다.");

    private final CouponIssueRequestStatus status;
    private final boolean requeuedOnResubmit;
    private final String message;

    CouponIssueRequestReason(CouponIssueRequestStatus status, boolean requeuedOnResubmit, String message) {
        this.status = status;
        this.requeuedOnResubmit = requeuedOnResubmit;
        this.message = message;
    }

    public CouponIssueRequestStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    /**
     * DB 작업 종료와 미발급을 확인한 시스템 실패만 사용자가 다시 접수할 때 같은 작업 ID로 다시 대기한다.
     */
    public boolean isRequeuedOnResubmit() {
        return requeuedOnResubmit;
    }

    public static CouponIssueRequestReason from(CouponNotIssuableException.Reason reason) {
        return switch (reason) {
            case SOLD_OUT -> SOLD_OUT;
            case OUT_OF_PERIOD -> CAMPAIGN_NOT_ISSUABLE;
        };
    }
}
