package com.loopers.application.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 쿠폰 발급 흐름(docs/week8/decisions.md D-8, D-10, D-12). 순서만 정한다: 기존 쿠폰 조회 → 자리 획득 → DB 발급 → 자리 반환.
 *
 * <ul>
 *   <li>기존 쿠폰이 있으면 자리를 잡지 않고 바로 돌려준다. 수용이 닫히거나 Redis가 죽어도 이미 받은 사람은 200을 받는다.
 *       "없음"을 봤다고 중복이 막히지는 않는다 — 마지막 방어선은 DB unique다(D-8).</li>
 *   <li>이 빈에는 TX가 없다. {@link CouponIssuer}가 돌아오면 발급 TX는 이미 끝났으므로, finally의 반환은 commit 뒤다.</li>
 *   <li>품절·비활성(409)·없는 캠페인(404)도 자리를 반환한다. 반환 실패는 결과를 바꾸지 않는다(D-10).</li>
 * </ul>
 */
@RequiredArgsConstructor
@Component
public class CouponIssueFacade {

    private final CouponQuery couponQuery;
    private final CouponAdmission couponAdmission;
    private final CouponIssuer couponIssuer;

    public CouponIssueResult issue(Long campaignId, Long userId) {
        Optional<IssuedCouponInfo> existing = couponQuery.findIssued(campaignId, userId);
        if (existing.isPresent()) {
            return CouponIssueResult.existing(existing.get());
        }

        String token = couponAdmission.acquire();
        try {
            return couponIssuer.issue(campaignId, userId);
        } finally {
            couponAdmission.release(token);
        }
    }
}
