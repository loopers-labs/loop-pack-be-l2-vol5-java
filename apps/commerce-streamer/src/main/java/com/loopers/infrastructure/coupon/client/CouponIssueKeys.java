package com.loopers.infrastructure.coupon.client;

import java.util.List;

/**
 * commerce-api 의 접수 키와 같은 이름을 쓴다. 두 앱이 같은 Redis 상태를 다룬다 (8주차 D-137).
 * 한 스크립트가 여러 키를 다루므로 해시 태그로 캠페인 id 를 묶는다
 */
record CouponIssueKeys(String epoch, String meta, String users, String stream, String delayed,
                       String reserve, String promoted, String soldOut, String pending) {

    static CouponIssueKeys of(long campaignId) {
        String prefix = "coupon-issue:{" + campaignId + "}:";
        return new CouponIssueKeys(
            prefix + "epoch",
            prefix + "meta",
            prefix + "users",
            prefix + "stream",
            prefix + "delayed",
            prefix + "reserve",
            prefix + "promoted",
            prefix + "sold-out",
            prefix + "pending-confirm"
        );
    }

    List<String> forConfirm() {
        return List.of(epoch, meta, users, pending, stream, reserve, soldOut, promoted);
    }

    List<String> forFail() {
        return List.of(users, stream, reserve, promoted, soldOut, meta);
    }
}
