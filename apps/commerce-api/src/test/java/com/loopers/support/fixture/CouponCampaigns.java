package com.loopers.support.fixture;

import com.loopers.application.coupon.admission.CouponAdmissionSwitch;
import com.loopers.domain.common.Money;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 테스트에서 발급을 받을 수 있는 캠페인을 연다. DB 캠페인 → Redis meta → 로컬 수용 스위치 순서로 준비한다.
 * 관리자 준비·열기 기능이 생기면 이 안만 그 기능으로 바꾼다.
 */
@Component
public class CouponCampaigns {

    public static final long DISCOUNT = 5_000;

    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponAdmissionState admissionState;
    private final CouponAdmissionSwitch admissionSwitch;
    private final List<Long> opened = new ArrayList<>();

    public CouponCampaigns(
        CouponCampaignRepository couponCampaignRepository,
        CouponAdmissionState admissionState,
        CouponAdmissionSwitch admissionSwitch
    ) {
        this.couponCampaignRepository = couponCampaignRepository;
        this.admissionState = admissionState;
        this.admissionSwitch = admissionSwitch;
    }

    public Long open(int quota) {
        Long campaignId = couponCampaignRepository.save(
            CouponCampaign.create("5,000원 할인권", new Money(DISCOUNT), quota)).getId();
        admissionState.meta(campaignId, true, true, quota);
        admissionSwitch.open();
        opened.add(campaignId);
        return campaignId;
    }

    /** 이 픽스처가 연 캠페인의 Redis 키만 지우고 스위치를 닫는다. DB 는 호출하는 테스트가 정리한다. */
    public void clear() {
        opened.forEach(admissionState::clear);
        opened.clear();
        admissionSwitch.close();
    }
}
