package com.loopers.interfaces.api.coupon;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 대기 등록 요청률 제한 대상 handler 표시(설계 §12.3). {@link CouponWaitingRateLimitInterceptor} 가 이 표시가 있는 handler 에만 허가를 쓴다.
 * 메서드 이름으로 대상을 고르면 이름 변경 때 제한이 조용히 빠지므로 표시로 고른다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CouponWaitingRateLimited {
}
