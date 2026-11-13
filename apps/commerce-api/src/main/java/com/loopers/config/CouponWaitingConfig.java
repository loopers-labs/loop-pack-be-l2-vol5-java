package com.loopers.config;

import com.loopers.interfaces.api.coupon.CouponWaitingRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 대기 등록 요청률 제한 interceptor 등록. 공용 WebMvcConfig 에 쿠폰 의존을 넣지 않으려고 분리했다. */
@RequiredArgsConstructor
@Configuration
public class CouponWaitingConfig implements WebMvcConfigurer {

    private final CouponWaitingRateLimitInterceptor couponWaitingRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(couponWaitingRateLimitInterceptor).addPathPatterns("/api/v1/coupon-campaigns/*/waiting");
    }
}
