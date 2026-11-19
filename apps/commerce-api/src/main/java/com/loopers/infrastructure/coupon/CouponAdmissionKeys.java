package com.loopers.infrastructure.coupon;

/**
 * 캠페인 단위 Redis 키 (8주차 설계 §4). 캐시 네임스페이스(products:v1:...)와 섞이지 않는다.
 */
final class CouponAdmissionKeys {

    private CouponAdmissionKeys() {}

    static String seq(Long campaignId) {
        return "coupon:" + campaignId + ":seq";
    }

    static String waiting(Long campaignId) {
        return "coupon:" + campaignId + ":waiting";
    }

    static String admitted(Long campaignId) {
        return "coupon:" + campaignId + ":admitted";
    }

    static String permits(Long campaignId) {
        return "coupon:" + campaignId + ":permits";
    }

    static String gen(Long campaignId) {
        return "coupon:" + campaignId + ":gen";
    }
}
