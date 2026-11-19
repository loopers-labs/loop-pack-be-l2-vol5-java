package com.loopers.domains.coupon.app.port;

import com.loopers.domains.coupon.domain.CouponCampaign;
import com.loopers.domains.coupon.domain.IssuedCoupon;
import java.util.Optional;

// ① 조회 결과: 캠페인과, 그 사용자가 이미 받은 쿠폰(없으면 빈 값).
public record CouponIssueLookup(CouponCampaign campaign, Optional<IssuedCoupon> coupon) {
}
