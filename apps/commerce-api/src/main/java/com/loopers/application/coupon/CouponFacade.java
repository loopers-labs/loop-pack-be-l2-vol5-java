package com.loopers.application.coupon;

import com.loopers.application.coupon.dto.CouponInfo;
import com.loopers.domain.coupon.CouponService;
import com.loopers.support.paging.PageRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 본인 보유 쿠폰 조회. Redis 를 쓰지 않으므로 수용 장애 중에도 응답한다. */
@RequiredArgsConstructor
@Component
public class CouponFacade {

    private final CouponService couponService;

    @Transactional(readOnly = true)
    public Page<CouponInfo> getMyList(Long userId, int page, int size) {
        return couponService.getList(userId, PageRequests.of(page, size)).map(CouponInfo::from);
    }
}
