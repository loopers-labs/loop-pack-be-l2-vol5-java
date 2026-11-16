package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * 기발급 결과와 품절만 저장하므로 없음은 '모름'이다.
 * 오류는 예외로 올리며 miss 로 바꾸는 것은 호출자의 몫이다.
 */
public interface CouponCacheRepository {

    Optional<IssuedCoupon> findIssued(Long userId, Long campaignId);

    void putIssuedIfAbsent(Long userId, Long campaignId, IssuedCoupon value, long ttlMillis);

    OptionalLong findSoldOutTtl(Long campaignId);

    void putSoldOutIfAbsent(Long campaignId, long ttlMillis);

    /** 값 불변식은 이 생성자 한곳에서 검사한다. 캐시 값과 DB 값이 같은 규칙을 거친다. */
    record IssuedCoupon(
        Long couponId,
        Long campaignId,
        String campaignName,
        CouponBenefitType benefitType,
        long benefitValue
    ) {
        public IssuedCoupon {
            if (couponId == null || couponId < 1 || campaignId == null || campaignId < 1) {
                throw new CoreException(ErrorType.INTERNAL_ERROR,
                    "캐시 발급 결과의 ID 가 올바르지 않습니다. [couponId = " + couponId + ", campaignId = " + campaignId + "]");
            }
            if (!CouponCampaignModel.isValidName(campaignName)) {
                throw new CoreException(ErrorType.INTERNAL_ERROR, "캐시 발급 결과의 캠페인 이름이 범위를 벗어났습니다.");
            }
            CouponBenefits.require(benefitType, benefitValue);
        }
    }
}
