package com.loopers.interfaces.api.coupon;

import com.loopers.config.CouponRateLimitProperties;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 대기 등록 요청률 제한. 사용자 DB 조회(인자 resolver) 전에 거절하려고 @RateLimiter 대신 interceptor 로 둔다.
 * 앱 인스턴스의 limiter 하나를 모든 사용자·캠페인이 공유하며 허가를 기다리지 않는다.
 */
@Component
public class CouponWaitingRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public CouponWaitingRateLimitInterceptor(CouponRateLimitProperties properties) {
        RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(properties.limitForPeriod())
            .limitRefreshPeriod(properties.refreshPeriod())
            .timeoutDuration(Duration.ZERO)
            .build();
        this.limiter = RateLimiter.of("couponWaiting", config);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isRegisterWaiting(request, handler) && !limiter.acquirePermission()) {
            throw new CoreException(ErrorType.COUPON_RATE_LIMITED);
        }
        return true;
    }

    private static boolean isRegisterWaiting(HttpServletRequest request, Object handler) {
        return "POST".equals(request.getMethod())
            && handler instanceof HandlerMethod method
            && method.hasMethodAnnotation(CouponWaitingRateLimited.class);
    }
}
