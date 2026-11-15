package com.loopers.domain.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 캠페인 준비 코드. 관리 화면이나 전체 CRUD 없이 로컬 fixture·테스트가 캠페인을 만들 때 쓴다. */
@RequiredArgsConstructor
@Component
public class CouponCampaignService {
    private final CouponCampaignRepository campaignRepository;

    @Transactional
    public CouponCampaignModel create(String name, int totalQuota, String benefitDescription) {
        return campaignRepository.save(new CouponCampaignModel(name, totalQuota, benefitDescription));
    }
}
