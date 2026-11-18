package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.gateway.CouponIssueQueue;
import com.loopers.domain.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** 사람이 로컬에서 부르는 캠페인 운영 동작. HTTP 로 노출하지 않음 (8주차 D-166, D-167) */
@Component
public class CouponCampaignOperations {

    private final CouponCampaignSteps couponCampaignSteps;
    private final CouponIssueQueue couponIssueQueue;
    private final UserRepository userRepository;
    private final Clock clock;
    private final double reserveRatio;

    public CouponCampaignOperations(
        CouponCampaignSteps couponCampaignSteps,
        CouponIssueQueue couponIssueQueue,
        UserRepository userRepository,
        Clock clock,
        @Value("${coupon-issue.reserve-ratio}") double reserveRatio
    ) {
        this.couponCampaignSteps = couponCampaignSteps;
        this.couponIssueQueue = couponIssueQueue;
        this.userRepository = userRepository;
        this.clock = clock;
        this.reserveRatio = reserveRatio;
    }

    /**
     * DB OPEN commit → Redis 메타 · 표식. Redis 쪽에서 멈추면 표식이 없어 접수는 닫힘으로 보이고,
     * 다시 부르면 이어서 열린다. epoch 는 여는 시각이라 재구성 때 새 값과 겹치지 않는다
     */
    public int open(Long campaignId) {
        CouponCampaign campaign = couponCampaignSteps.open(campaignId);
        int reserveLimit = campaign.reserveLimit(reserveRatio);
        couponIssueQueue.open(campaignId, campaign.getTotalQuota(), reserveLimit, String.valueOf(clock.millis()));
        return reserveLimit;
    }

    /** DB CLOSED commit → Redis 새 접수 차단 · 품절 플래그 */
    public void close(Long campaignId) {
        couponCampaignSteps.close(campaignId);
        couponIssueQueue.close(campaignId);
    }

    public void createUsers(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("만들 사용자 수는 1 이상. count=" + count);
        }
        userRepository.createUsers(count);
    }
}
