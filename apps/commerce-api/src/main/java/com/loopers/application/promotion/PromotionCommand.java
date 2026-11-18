package com.loopers.application.promotion;

import java.time.ZonedDateTime;
import java.util.Map;

/** 프로모션 생성 입력. type 은 종류 이름 문자열이다. 검증은 Facade·Model 이 한다 (INVALID_PROMOTION). */
public record PromotionCommand(
    String name,
    String type,
    ZonedDateTime startsAt,
    ZonedDateTime endsAt,
    CouponIssue couponIssue
) {
    /** 종류 COUPON_ISSUE 의 설정. */
    public record CouponIssue(Integer totalQuantity, Integer perUserLimit, Map<String, Object> couponSpec) {
    }
}
