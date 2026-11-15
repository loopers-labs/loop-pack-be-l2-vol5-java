package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 쿠폰 발급 요청(POST /api/v1/coupon-campaigns/{id}/issues)의 초당 한도를 전역으로 하나 둔다.
 * 한도를 넘으면 컨트롤러에 닿기 전에 429 {@code RATE_LIMITED}와 {@code Retry-After}(한도가 다시 채워질 때까지 남은 시간)로 거절한다.
 * 자리 없음({@code NO_PERMIT}) 429와는 오류 코드로 구분한다. Retry-After는 재시도 시각 안내일 뿐 쿠폰 확보를 뜻하지 않는다.
 */
@Component
@ConditionalOnProperty(name = "coupon.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class CouponIssueRateLimitFilter extends OncePerRequestFilter {

    private static final String ISSUE_PATH = "/api/v1/coupon-campaigns/*/issues";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final RateLimiter rateLimiter;
    private final Duration refreshPeriod;
    private final ObjectMapper objectMapper;

    public CouponIssueRateLimitFilter(CouponRateLimitProperties properties, ObjectMapper objectMapper) {
        this.refreshPeriod = properties.refreshPeriod();
        this.objectMapper = objectMapper;
        // timeout 0: 기다리지 않고 바로 거절한다
        this.rateLimiter = RateLimiter.of("coupon-issue", RateLimiterConfig.custom()
            .limitForPeriod(properties.limitForPeriod())
            .limitRefreshPeriod(properties.refreshPeriod())
            .timeoutDuration(Duration.ZERO)
            .build());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !pathMatcher.match(ISSUE_PATH, request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (rateLimiter.acquirePermission()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = retryAfterSeconds();
        response.setStatus(ErrorType.RATE_LIMITED.getStatus().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
            ApiResponse.fail(ErrorType.RATE_LIMITED.getCode(), ErrorType.RATE_LIMITED.getMessage()));
    }

    /** 한도가 다시 채워질 때까지 남은 시간(올림, 최소 1초). 알 수 없으면 한 주기를 안내한다. */
    private long retryAfterSeconds() {
        long nanos = rateLimiter.getMetrics() instanceof AtomicRateLimiter.AtomicRateLimiterMetrics metrics
            ? metrics.getNanosToWait()
            : refreshPeriod.toNanos();
        long seconds = Duration.ofNanos(Math.max(nanos, 0)).plusMillis(999).toSeconds();
        return Math.max(1, seconds);
    }
}
