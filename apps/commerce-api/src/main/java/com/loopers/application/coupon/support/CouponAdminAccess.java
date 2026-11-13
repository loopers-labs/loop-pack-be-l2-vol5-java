package com.loopers.application.coupon.support;

import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;

/** 기존 헤더 resolver가 확인한 사용자를 대상으로 하는 학습용 관리자 fixture. */
public final class CouponAdminAccess {
    private CouponAdminAccess() {}

    public static void requireAdmin(long userId) {
        if (userId != 1L) {
            throw new ApplicationException(ApplicationErrorCode.COUPON_ADMIN_FORBIDDEN);
        }
    }
}
