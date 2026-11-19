package com.loopers.domains.coupon.domain;

// 한정 수량 쿠폰 캠페인. 발급 가능 판정은 ① 조회(잠금 없는 읽기)에서 쓰고, 같은 조건을 ④의 조건부 UPDATE가 다시 검사한다(D128).
public record CouponCampaign(long id, String name, boolean active, int totalQuota, int issuedCount, long discountAmount) {
    public boolean canIssue() {
        return active && issuedCount < totalQuota;
    }
}
