package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponSeedService;
import com.loopers.domain.coupon.CouponCampaign;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("local")
@Order(0)
public class CouponSeedRunner implements ApplicationRunner {

    private final CouponSeedService seed;
    private final boolean reset;

    public CouponSeedRunner(CouponSeedService seed, @Value("${coupon.seed.reset:false}") boolean reset) {
        this.seed = seed;
        this.reset = reset;
    }

    @Override
    public void run(ApplicationArguments args) {
        CouponCampaign campaign = seed.prepare(reset);
        log.info("로컬 쿠폰 seed를 준비했습니다. campaignId={}, totalQuota={}, reset={}",
            campaign.getId(), campaign.getTotalQuota(), reset);
    }
}
