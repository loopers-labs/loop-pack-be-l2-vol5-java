package com.loopers.coupon.domain;

/**
 * 쿠폰 유효 기간을 정하는 방식 (CPN-06·07). FIXED_PERIOD는 정해진 시작·끝, DAYS_FROM_ISSUE는 발급 시각부터 N일.
 */
public enum ValidityType {
    FIXED_PERIOD,
    DAYS_FROM_ISSUE
}
