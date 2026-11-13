package com.loopers.application.coupon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 로컬 부하 측정 전용 준비(docs/week8/load-test/README.md). {@code coupon-load} 프로필에서만 빈이 된다.
 *
 * <p>서버가 다 켜지면 품절이 측정에 섞이지 않도록 한도가 큰 캠페인을 하나 만들고, 수용을 연다.
 * 캠페인 준비와 수용 열기는 HTTP 없이 빈 메서드로만 부른다(docs/week8/decisions.md D-11, D-16) — 이 클래스가 그 "로컬 실행 코드"다.
 * 서버는 기본 닫힘(D-7)이므로, 이 프로필 없이 띄운 서버는 지금처럼 닫힌 채 시작한다.
 * 측정 스크립트는 로그의 {@code coupon-load campaignId=} 줄에서 캠페인 id를 읽는다(local은 매번 스키마를 새로 만들어 보통 1이다).
 */
@Slf4j
@Profile("coupon-load")
@Component
public class CouponLoadRunPreparation {

    private final CouponCampaignSetup couponCampaignSetup;
    private final CouponAdmission couponAdmission;
    private final int quota;

    public CouponLoadRunPreparation(
        CouponCampaignSetup couponCampaignSetup,
        CouponAdmission couponAdmission,
        @Value("${coupon.load.quota:1000000}") int quota
    ) {
        this.couponCampaignSetup = couponCampaignSetup;
        this.couponAdmission = couponAdmission;
        this.quota = quota;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepare() {
        CouponCampaignInfo campaign = couponCampaignSetup.create("부하 측정 캠페인", quota, "5,000원 할인", 5_000L);
        couponAdmission.open();
        log.warn("coupon-load campaignId={} quota={} capacity={}", campaign.campaignId(), quota, couponAdmission.capacity());
    }
}
