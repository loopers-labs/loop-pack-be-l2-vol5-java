package com.loopers.domain.coupon;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface CouponQueryRepository {

    /**
     * 본인의 해당 캠페인 쿠폰을 연결 캠페인 이름과 함께 조회한다. 쿠폰이 없으면 빈 값이다.
     * 쿠폰은 있으나 연결 캠페인이 없으면 '없음'으로 바꾸지 않고 INTERNAL_ERROR 로 드러낸다.
     */
    Optional<OwnedCoupon> findOwned(Long userId, Long campaignId);

    /**
     * 본인 쿠폰을 발급 시각·ID 로 안정 정렬해 조회한다. 총건수는 본인 쿠폰 수이며 캠페인 상태와 무관하다.
     * 페이지 안의 쿠폰에 연결 캠페인이 없으면 INTERNAL_ERROR 다.
     */
    PageResult<OwnedCoupon> findOwnedPage(Long userId, PageCommand page, ListSort sort);
}
