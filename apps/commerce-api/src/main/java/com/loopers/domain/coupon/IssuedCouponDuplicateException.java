package com.loopers.domain.coupon;

/**
 * 쿠폰 저장이 (campaign_id, user_id) unique({@link IssuedCoupon#UK_CAMPAIGN_USER})에 걸렸다는 신호(INV-802, D-8).
 * 이 예외를 본 발급 TX는 rollback되고(발급 수 증가도 함께), 호출자는 새 TX에서 기존 쿠폰을 조회해 같은 couponId를 돌려준다.
 * 응답 코드로 바로 바뀌지 않도록 CoreException이 아니다 — 성공(200)으로 바꿀지는 호출자가 다시 조회해 정한다.
 */
public class IssuedCouponDuplicateException extends RuntimeException {

    public IssuedCouponDuplicateException(Long campaignId, Long userId, Throwable cause) {
        super("이미 발급된 쿠폰입니다. (campaignId=" + campaignId + ", userId=" + userId + ")", cause);
    }
}
