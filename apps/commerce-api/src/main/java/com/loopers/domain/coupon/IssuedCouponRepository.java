package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

/**
 * 발급된 쿠폰 저장소 포트. 구현은 infrastructure/coupon의 JPA다. 쿠폰을 지우거나 바꾸는 메서드는 두지 않는다(INV-806).
 */
public interface IssuedCouponRepository {

    /**
     * 저장하고 바로 flush한다 — unique 위반이 commit 때가 아니라 발급 TX 안에서 드러나게 하기 위해서다(D-8).
     *
     * @throws IssuedCouponDuplicateException (campaign_id, user_id) unique에 걸렸다. 같은 사용자가 이미 받았다.
     *         이 제약이 아닌 오류(다른 제약, 연결 오류)는 바꾸지 않고 그대로 던진다.
     */
    IssuedCoupon save(IssuedCoupon coupon);

    Optional<IssuedCoupon> findByCampaignIdAndUserId(Long campaignId, Long userId);

    /** 그 사용자의 쿠폰만, 발급 시각 내림차순, 같으면 id 내림차순(D-16). */
    List<IssuedCoupon> findAllByUserId(Long userId);
}
