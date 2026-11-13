package com.loopers.infrastructure.initializer.coupon;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.result.CouponCampaignResult;
import com.loopers.application.coupon.usecase.PrepareCouponCampaignUseCase;
import com.loopers.application.coupon.usecase.SetCouponCampaignActivationUseCase;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 명시적으로 선택한 작은 로컬 fixture. 평소 시작/재시작에서는 실행하지 않는다. */
@Component
@Profile("coupon-fixture")
@ConditionalOnProperty(prefix = "coupon.fixture", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LocalCouponCampaignFixture implements ApplicationRunner {
    private final PrepareCouponCampaignUseCase prepareUseCase;
    private final SetCouponCampaignActivationUseCase activationUseCase;

    @Value("${coupon.fixture.create:false}")
    private boolean create;
    @Value("${coupon.fixture.campaign-id:0}")
    private long campaignId;
    @Value("${coupon.fixture.activate:false}")
    private boolean activate;

    @Override
    public void run(ApplicationArguments args) {
        if ((create && campaignId != 0) || (!create && campaignId <= 0)) {
            throw new IllegalArgumentException("새 fixture 생성 또는 기존 campaign-id 중 하나를 명시해야 합니다.");
        }
        CouponCampaignResult result = create
            ? prepareUseCase.execute(new CouponCampaignCommand.Create("로컬 쿠폰 캠페인", 3, "1,000원 할인", 1_000,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z")))
            : prepareUseCase.execute(new CouponCampaignCommand.Prepare(campaignId));
        log.info("로컬 쿠폰 fixture 준비 TX 완료: {}", result);
        if (activate) {
            result = activationUseCase.execute(new CouponCampaignCommand.Activation(result.campaignId(), true));
            log.info("로컬 쿠폰 fixture 활성화 TX 완료: {}", result);
        }
    }
}
