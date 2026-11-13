package com.loopers.interfaces.api.coupon;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 요청률 제한 대상 handler 표시. 메서드 이름으로 고르면 이름 변경 때 제한이 조용히 빠져서 표시로 고른다. */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CouponWaitingRateLimited {
}
