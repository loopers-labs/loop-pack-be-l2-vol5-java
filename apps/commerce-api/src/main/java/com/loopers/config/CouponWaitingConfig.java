package com.loopers.config;

import com.loopers.interfaces.api.coupon.CouponWaitingRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 대기 등록 요청률 제한 interceptor 의 MVC 등록만 맡는다. 공용 WebMvcConfig 에 쿠폰 의존을 넣지 않으려고 분리했다
 * (주문 이벤트 재현 컨텍스트가 쿠폰 bean 을 이름으로 뺄 때 공용 설정이 깨지지 않게 한다).
 * 경로는 대기 등록·조회 공통이며, 조회는 interceptor 가 handler 를 보고 그대로 통과시킨다.
 */
@RequiredArgsConstructor
@Configuration
public class CouponWaitingConfig implements WebMvcConfigurer {

    private final CouponWaitingRateLimitInterceptor couponWaitingRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(couponWaitingRateLimitInterceptor).addPathPatterns("/api/v1/coupon-campaigns/*/waiting");
    }
}
