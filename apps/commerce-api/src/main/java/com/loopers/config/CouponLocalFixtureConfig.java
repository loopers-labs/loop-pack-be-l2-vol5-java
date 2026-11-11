package com.loopers.config;

import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 직접 실행용 쿠폰 준비 데이터(설계 §9). {@code commerce.coupon.fixture.enabled=true} 를 명시한 앱 시작에서만 등록돼 한 번 실행된다.
 * 매 실행마다 새 사용자 8명(잔액 0 Point)과 새 ACTIVE 캠페인 1개를 한 TX 로 추가한다. 이름으로 기존 캠페인을 찾거나 기존 데이터를
 * 바꾸지 않고, 쿠폰을 만들지 않으며, Redis 와 수용 상태에 손대지 않는다(수용은 기본 닫힘 그대로).
 * 준비 실패는 예외로 앱 기동을 멈춘다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "commerce.coupon.fixture.enabled", havingValue = "true")
public class CouponLocalFixtureConfig {

    static final String CAMPAIGN_NAME = "로컬 쿠폰 캠페인";
    static final int USER_COUNT = 8;
    static final long TOTAL_QUOTA = 5;
    static final long BENEFIT_VALUE = 5_000L;

    @Bean
    ApplicationRunner couponLocalFixtureRunner(
        PlatformTransactionManager transactionManager,
        UserJpaRepository userJpaRepository,
        PointJpaRepository pointJpaRepository,
        CouponCampaignJpaRepository campaignJpaRepository
    ) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return args -> {
            Prepared prepared = transactionTemplate.execute(status -> {
                List<Long> userIds = new ArrayList<>();
                for (int i = 0; i < USER_COUNT; i++) {
                    Long userId = userJpaRepository.save(UserModel.create()).getId();
                    pointJpaRepository.save(PointModel.of(userId));
                    userIds.add(userId);
                }
                Long campaignId = campaignJpaRepository.save(
                    CouponCampaignModel.create(CAMPAIGN_NAME, TOTAL_QUOTA, CouponBenefitType.FIXED_AMOUNT, BENEFIT_VALUE)).getId();
                return new Prepared(List.copyOf(userIds), campaignId);
            });
            log.info("로컬 쿠폰 준비 완료(commit). 수용은 닫혀 있으며 POST /api/coupon/admission/open 으로 연다. "
                    + "[campaignId = {}, userIds = {}, 예: POST /api/v1/coupon-campaigns/{}/issues, X-USER-ID: {}]",
                prepared.campaignId(), prepared.userIds(), prepared.campaignId(), prepared.userIds().get(0));
        };
    }

    record Prepared(List<Long> userIds, Long campaignId) {
    }
}
