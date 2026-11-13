package com.loopers.application.promotion.query;

import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 쿠폰 조회 유스케이스 (DR-31). */
@RequiredArgsConstructor
@Component
public class CouponReader {
    private final UserService userService;
    private final CouponQueryRepository couponQueryRepository;

    /** 내 쿠폰 목록. 페이지 없이 전부 돌려준다. */
    @Transactional(readOnly = true)
    public List<CouponView.MyCoupon> listMyCoupons(Long requesterId) {
        userService.getUser(requesterId);
        return couponQueryRepository.findMyCoupons(requesterId);
    }
}
