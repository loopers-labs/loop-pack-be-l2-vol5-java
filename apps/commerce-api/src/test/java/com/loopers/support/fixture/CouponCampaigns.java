package com.loopers.support.fixture;

import com.loopers.application.coupon.AdminCouponCampaignFacade;
import com.loopers.application.coupon.admission.CouponAdmissionSwitch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 테스트에서 발급을 받을 수 있는 캠페인을 연다. 운영과 같은 관리자 절차(준비 → 열기)를 지난다.
 */
@Component
public class CouponCampaigns {

    public static final long DISCOUNT = 5_000;

    private final AdminCouponCampaignFacade adminCouponCampaignFacade;
    private final CouponAdmissionState admissionState;
    private final CouponAdmissionSwitch admissionSwitch;
    private final List<Long> opened = new ArrayList<>();

    public CouponCampaigns(
        AdminCouponCampaignFacade adminCouponCampaignFacade,
        CouponAdmissionState admissionState,
        CouponAdmissionSwitch admissionSwitch
    ) {
        this.adminCouponCampaignFacade = adminCouponCampaignFacade;
        this.admissionState = admissionState;
        this.admissionSwitch = admissionSwitch;
    }

    public Long open(int quota) {
        Long campaignId = adminCouponCampaignFacade.prepare("5,000원 할인권", DISCOUNT, quota).id();
        opened.add(campaignId);
        adminCouponCampaignFacade.open(campaignId);
        return campaignId;
    }

    /** 이 픽스처가 연 캠페인의 Redis 키만 지우고 스위치를 닫는다. DB 는 호출하는 테스트가 정리한다. */
    public void clear() {
        opened.forEach(admissionState::clear);
        opened.clear();
        admissionSwitch.close();
    }
}
