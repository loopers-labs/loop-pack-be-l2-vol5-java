package com.loopers.coupon.adapter.in.web;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 쿠폰이 서버 자원을 다 쓰지 않게 하는 격벽 (D-6). 지키는 자원(커넥션 풀·Tomcat 스레드)이 서버마다 따로 있어 서버 메모리 안에서 센다.
 * <ul>
 *   <li>발급: Bulkhead {@code couponIssue} — 동시 처리 수 상한. 대기열은 "들어오는 수"만 정해서, DB가 느려져 한 건이 길어지면
 *       동시 처리 수가 커진다(Little's law). 그때 끊는 퓨즈다. 판정 Lua 앞에서 막으므로 막혀도 아무 일도 없고, 입장 키가 살아 있어 다시 요청하면 된다.</li>
 *   <li>대기열 등록·조회: RateLimiter {@code couponQueue} — 서버당 초당 요청 수. 넘으면 429 + Retry-After.</li>
 * </ul>
 * 값은 실측(필수 실험 ⑨)으로 정한다.
 */
@Component
public class CouponIsolation {

    private final Bulkhead issueBulkhead;
    private final RateLimiter queueRateLimiter;

    public CouponIsolation(BulkheadRegistry bulkheadRegistry, RateLimiterRegistry rateLimiterRegistry) {
        this.issueBulkhead = bulkheadRegistry.bulkhead("couponIssue");
        this.queueRateLimiter = rateLimiterRegistry.rateLimiter("couponQueue");
    }

    public <T> T issue(Supplier<T> call) {
        try {
            return Bulkhead.decorateSupplier(issueBulkhead, call).get();
        } catch (BulkheadFullException e) {
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "발급 요청이 몰려 처리하지 못했습니다. 입장은 유지되니 잠시 후 다시 시도해 주세요.");
        }
    }

    public <T> T queue(Supplier<T> call) {
        try {
            return RateLimiter.decorateSupplier(queueRateLimiter, call).get();
        } catch (RequestNotPermitted e) {
            throw new CoreException(ErrorType.TOO_MANY_REQUESTS);
        }
    }
}
