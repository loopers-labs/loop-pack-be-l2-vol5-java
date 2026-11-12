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
 * 대기 등록 입구의 요청률 제한(설계 §12.3). 앱 인스턴스에 limiter 하나를 두고 모든 사용자·캠페인이 공유한다.
 *
 * <p>MVC 매핑 뒤·인자 resolver(사용자 DB 조회) 전에 실행돼야 하므로 @RateLimiter 대신 interceptor 로 둔다.
 * 허가는 기다리지 않고(timeout 0) 요청 결과와 무관하게 반환하지 않는다. {@link CouponWaitingRateLimited} 를 붙인 대기 등록 handler 외에는 허가를 쓰지 않는다.
 * 설정이 Resilience4j 의 표현 범위를 넘으면 생성자에서 실패해 기동이 멈춘다.
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
