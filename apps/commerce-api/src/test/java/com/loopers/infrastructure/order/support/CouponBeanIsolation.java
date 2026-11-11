package com.loopers.infrastructure.order.support;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import java.util.List;

/**
 * 주문 이벤트 재현 컨텍스트(W6 준비·O11 의 W7 기동)에서 쿠폰 bean 정의를 정확한 이름으로 뺀다(발행 W7.4-1·W7.4-2).
 * 두 컨텍스트는 쿠폰 도입 전 관리 타입만 허용하므로 쿠폰 repository 가 남으면 기동에 실패한다.
 * 일반 테스트·제품 컨텍스트에는 적용하지 않는다. 이름 접두사·패키지 일괄 제거는 쓰지 않으며, 쿠폰 bean 이 늘면 이 목록에 더한다.
 */
public final class CouponBeanIsolation {

    public static final List<String> COUPON_DEFINITIONS = List.of(
        "couponCampaignJpaRepository",
        "couponCampaignRepositoryImpl",
        "couponJpaRepository",
        "couponRepositoryImpl",
        "couponQueryRepositoryImpl",
        "couponFixture");

    private CouponBeanIsolation() {}

    /** 하나라도 없으면 조립이 바뀐 것이므로 기동을 거절한다. 확인을 모두 마친 뒤에만 제거한다. */
    public static void remove(BeanDefinitionRegistry registry) {
        for (String name : COUPON_DEFINITIONS) {
            if (!registry.containsBeanDefinition(name)) {
                throw new IllegalStateException("제거할 쿠폰 bean 정의가 없습니다. [bean = " + name + "]");
            }
        }
        COUPON_DEFINITIONS.forEach(registry::removeBeanDefinition);
    }
}
