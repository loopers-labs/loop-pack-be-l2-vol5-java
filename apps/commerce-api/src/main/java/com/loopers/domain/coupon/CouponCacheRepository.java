package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * 기발급 결과(positive)와 캠페인 품절(true)의 캐시 저장소(설계 §13.9). 미발급·재고 있음은 저장하지 않으므로 없음은 '모름'이다.
 * 저장은 키가 없을 때만 TTL 과 함께 쓰고, 있으면 값·TTL 을 바꾸지 않는다. 조회는 TTL 을 연장하지 않으며 TTL 없는 키는 없음이다.
 * 기술 오류·손상 값은 예외로 올리며 정상 없음과 구분한다. 오류를 miss 로 바꾸는 것은 호출자의 몫이다.
 */
public interface CouponCacheRepository {

    Optional<IssuedCoupon> findIssued(Long userId, Long campaignId);

    void putIssuedIfAbsent(Long userId, Long campaignId, IssuedCoupon value, long ttlMillis);

    /** 품절 표시의 남은 밀리초. 없으면 empty. */
    OptionalLong findSoldOutTtl(Long campaignId);

    void putSoldOutIfAbsent(Long campaignId, long ttlMillis);

    /**
     * 캐시에 두는 발급 결과 다섯 값. 발급 시각·created·대기 정보는 두지 않는다.
     * 값 불변식은 이 생성자 한곳에서 검사한다. 캐시에서 읽은 값과 DB 결과에서 만든 값 모두 같은 규칙을 거친다.
     */
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
