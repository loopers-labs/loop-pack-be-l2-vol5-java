package com.loopers.application.coupon.admission;

/**
 * 쿠폰 발급의 당첨 판정과 DB 실행 자리(세마포어)를 다루는 포트. 판정은 도착 순서대로 원자적으로 이뤄진다.
 * 상태를 확인할 수 없으면 {@link CouponAdmissionException} 을 던진다.
 */
public interface CouponIssueAdmission {

    ReserveResult reserve(Long campaignId, Long userId, String token);

    AcquireOutcome acquire(Long campaignId, Long userId, String token);

    /** 202 로 약속한 당첨임을 표시한다. DB 에 당첨 기록을 남긴 뒤 부른다. */
    void markTicketed(Long campaignId, Long userId);

    /** 자기 token 만 반환한다. {@code couponId} 가 있으면 결과로 기록한다. */
    void finish(Long campaignId, Long userId, String token, Long couponId);
}
