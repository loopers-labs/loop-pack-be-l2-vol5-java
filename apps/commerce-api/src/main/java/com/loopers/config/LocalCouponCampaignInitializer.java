package com.loopers.config;

import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 실행에서 실습용 캠페인을 READY 로 하나 만든다. 열기는 runner 로 명시적으로 한다 (8주차 D-165, D-166).
 * 한도 · 혜택은 설정으로 바꿔 실험마다 코드를 고치지 않는다
 */
@Slf4j
@Profile("local")
@Component
public class LocalCouponCampaignInitializer implements ApplicationRunner {

    private final CouponCampaignRepository couponCampaignRepository;
    private final int totalQuota;
    private final DiscountType discountType;
    private final long discountValue;
    private final String description;

    public LocalCouponCampaignInitializer(
        CouponCampaignRepository couponCampaignRepository,
        @Value("${coupon-campaign.local.total-quota:100}") int totalQuota,
        @Value("${coupon-campaign.local.discount-type:FIXED_AMOUNT}") DiscountType discountType,
        @Value("${coupon-campaign.local.discount-value:5000}") long discountValue,
        @Value("${coupon-campaign.local.description:5,000원 할인권}") String description
    ) {
        this.couponCampaignRepository = couponCampaignRepository;
        this.totalQuota = totalQuota;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.description = description;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (couponCampaignRepository.count() > 0) {
            return;
        }
        CouponCampaign campaign = couponCampaignRepository.save(
            new CouponCampaign("실습용 선착순 쿠폰", totalQuota, discountType, discountValue, description));
        log.info("로컬 실습용 쿠폰 캠페인을 READY 로 만들었습니다. campaignId={} totalQuota={}", campaign.getId(), totalQuota);
    }
}
