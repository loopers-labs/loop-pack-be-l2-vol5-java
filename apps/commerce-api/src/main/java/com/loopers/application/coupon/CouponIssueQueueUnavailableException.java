package com.loopers.application.coupon;

/**
 * 대기열 저장소에 연결하지 못했거나 응답을 받지 못했다. 명령이 실제로 반영됐는지는 알 수 없다.
 */
public class CouponIssueQueueUnavailableException extends RuntimeException {

    public CouponIssueQueueUnavailableException(Throwable cause) {
        super("쿠폰 발급 대기열 상태를 확인할 수 없습니다.", cause);
    }
}
