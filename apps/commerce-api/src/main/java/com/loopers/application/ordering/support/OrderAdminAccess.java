package com.loopers.application.ordering.support;

import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;

/** 학습용 주문 관리자 fixture. 호출 전 기존 헤더 resolver가 사용자 존재를 확인한다. */
public final class OrderAdminAccess {
    private OrderAdminAccess() {}

    public static void requireAdmin(long userId) {
        if (userId != 1L) { throw new ApplicationException(ApplicationErrorCode.ORDER_ADMIN_FORBIDDEN); }
    }
}
