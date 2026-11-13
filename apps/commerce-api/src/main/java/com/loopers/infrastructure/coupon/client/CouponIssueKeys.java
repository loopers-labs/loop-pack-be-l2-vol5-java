package com.loopers.infrastructure.coupon.client;

import java.util.List;

/**
 * 캠페인 하나의 접수 상태를 담는 Redis 키. 캐시 키와 섞지 않는 전용 이름을 쓴다.
 * 한 스크립트가 여러 키를 다루므로 Redis Cluster 에서도 같은 슬롯에 놓이게 해시 태그로 캠페인 id 를 묶는다
 */
record CouponIssueKeys(String epoch, String sequence, String users, String pending,
                       String stream, String reserve, String soldOut, String promoted) {

    static CouponIssueKeys of(long campaignId) {
        String prefix = "coupon-issue:{" + campaignId + "}:";
        return new CouponIssueKeys(
            prefix + "epoch",
            prefix + "sequence",
            prefix + "users",
            prefix + "pending-confirm",
            prefix + "stream",
            prefix + "reserve",
            prefix + "sold-out",
            prefix + "promoted"
        );
    }

    List<String> forAccept() {
        return List.of(epoch, sequence, users, pending, soldOut, promoted);
    }

    List<String> forConfirm() {
        return List.of(epoch, users, pending, stream, reserve, soldOut, promoted);
    }

    List<String> forRead() {
        return List.of(epoch, users, soldOut, promoted);
    }
}
