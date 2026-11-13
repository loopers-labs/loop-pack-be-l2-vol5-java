package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponService {

    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponRepository couponRepository;

    /**
     * 쿠폰을 저장하고 발급 수를 올린다. 트랜잭션은 호출하는 쪽이 연다.
     *
     * <p>insert 를 먼저 해 같은 사용자 쿠폰이 있으면 유일 제약 충돌이 먼저 드러나게 한다. 발급 수가 이미 한도면
     * {@code COUPON_SOLD_OUT} 을 던져 넣은 쿠폰까지 rollback 되게 한다.
     */
    public Coupon issue(Long campaignId, Long userId, ZonedDateTime now) {
        CouponCampaign campaign = couponCampaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
        Coupon coupon = couponRepository.insert(Coupon.issue(campaign, userId, now));
        if (couponCampaignRepository.increaseIssuedCount(campaignId) == 0) {
            throw new CoreException(ErrorType.COUPON_SOLD_OUT);
        }
        return coupon;
    }

    public Optional<Coupon> findIssued(Long campaignId, Long userId) {
        return couponRepository.findByCampaignIdAndUserId(campaignId, userId);
    }
}
