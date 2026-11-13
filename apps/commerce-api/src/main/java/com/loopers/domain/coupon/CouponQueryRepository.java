package com.loopers.domain.coupon;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface CouponQueryRepository {

    /** 쿠폰은 있으나 연결 캠페인이 없으면 '없음'이 아니라 INTERNAL_ERROR 다. */
    Optional<OwnedCoupon> findOwned(Long userId, Long campaignId);

    /** 발급 시각·ID 로 안정 정렬한다. 연결 캠페인이 없는 쿠폰은 INTERNAL_ERROR 다. */
    PageResult<OwnedCoupon> findOwnedPage(Long userId, PageCommand page, ListSort sort);
}
