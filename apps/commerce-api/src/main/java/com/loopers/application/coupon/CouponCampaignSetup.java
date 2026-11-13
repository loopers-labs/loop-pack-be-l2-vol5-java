package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponBenefit;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 준비(docs/week8/decisions.md D-11, D-16). HTTP 주소는 만들지 않는다 — 테스트와 로컬 실행 코드만 이 빈 메서드를 부른다.
 * 운영의 /api-admin/**은 로그인 수단이 없어, HTTP로 열면 아무도 못 부르거나 아무나 부르게 된다.
 *
 * <p>캠페인 행을 바꾸는 메서드는 발급 TX와 같은 {@code FOR UPDATE}로 읽는다(D-8). 일반 조회로 읽고 덮어쓰면 그 사이 발급 TX가
 * 올린 발급 수를 지울 수 있다(3주차 관리자 상품 수정과 같은 lost update).
 */
@RequiredArgsConstructor
@Component
public class CouponCampaignSetup {

    private final CouponCampaignRepository couponCampaignRepository;

    @Transactional
    public CouponCampaignInfo create(String name, int totalQuota, String benefitDescription, long discountAmount) {
        CouponCampaign campaign = new CouponCampaign(name, totalQuota, CouponBenefit.of(benefitDescription, discountAmount));
        return CouponCampaignInfo.from(couponCampaignRepository.save(campaign));
    }

    /** 신규 발급을 멈춘다. 이미 발급된 쿠폰과 발급 수는 그대로다. */
    @Transactional
    public CouponCampaignInfo deactivate(Long campaignId) {
        CouponCampaign campaign = getForUpdate(campaignId);
        campaign.deactivate();
        return CouponCampaignInfo.from(couponCampaignRepository.save(campaign));
    }

    /** 앞으로 발급할 쿠폰의 혜택을 바꾼다. 이미 발급된 쿠폰은 바뀌지 않는다(INV-806). */
    @Transactional
    public CouponCampaignInfo changeBenefit(Long campaignId, String benefitDescription, long discountAmount) {
        CouponCampaign campaign = getForUpdate(campaignId);
        campaign.changeBenefit(CouponBenefit.of(benefitDescription, discountAmount));
        return CouponCampaignInfo.from(couponCampaignRepository.save(campaign));
    }

    @Transactional(readOnly = true)
    public CouponCampaignInfo get(Long campaignId) {
        return couponCampaignRepository.findById(campaignId)
            .map(CouponCampaignInfo::from)
            .orElseThrow(() -> notFound(campaignId));
    }

    private CouponCampaign getForUpdate(Long campaignId) {
        return couponCampaignRepository.findByIdForUpdate(campaignId).orElseThrow(() -> notFound(campaignId));
    }

    private static CoreException notFound(Long campaignId) {
        return new CoreException(ErrorType.NOT_FOUND, "[id = " + campaignId + "] 쿠폰 캠페인을 찾을 수 없습니다.");
    }
}
