package com.loopers.domains.coupon.app.port;

import com.loopers.domains.coupon.domain.IssuedCoupon;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponRepository {
    // 캠페인 LEFT JOIN 그 사용자의 보유 쿠폰. 잠금 없이 한 번 읽는다(D127). 캠페인이 없으면 빈 값이다.
    Optional<CouponIssueLookup> findForIssue(long campaignId, long userId);

    // active이고 issued_count < total_quota일 때만 issued_count를 1 올리고 영향 행 수를 돌려준다(D125). 발급 트랜잭션 안에서 부른다.
    int increaseIssued(long campaignId);

    // 캠페인의 혜택을 다시 읽어 복사한 쿠폰을 넣는다(D131). 같은 캠페인·사용자가 이미 있으면 DuplicateIssueException을 던진다.
    IssuedCoupon insert(long campaignId, long userId, Instant issuedAt);

    // 본인 쿠폰만 issued_at DESC, id DESC.
    List<IssuedCoupon> findByUser(long userId);
}
