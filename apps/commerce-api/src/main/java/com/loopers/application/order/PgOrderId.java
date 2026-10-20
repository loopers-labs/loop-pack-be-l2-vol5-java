package com.loopers.application.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/** PG 가 받는 주문 ID 형식 (6자 이상 문자열). 우리 주문 ID(Long) ↔ "ORDER-{id}". 저장하지 않고 PG 경계에서만 바꾼다. */
public final class PgOrderId {
    private static final String PREFIX = "ORDER-";

    private PgOrderId() {}

    public static String of(Long orderId) {
        return PREFIX + orderId;
    }

    /** 형식이 아니면 ER-05 ORDER_NOT_FOUND (callback 으로 잘못된 ID 가 오면 없는 주문으로 본다). */
    public static Long parse(String pgOrderId) {
        if (pgOrderId == null || !pgOrderId.startsWith(PREFIX)) {
            throw notFound(pgOrderId);
        }
        try {
            return Long.parseLong(pgOrderId.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            throw notFound(pgOrderId);
        }
    }

    private static CoreException notFound(String pgOrderId) {
        return new CoreException(ErrorType.ORDER_NOT_FOUND, "[pgOrderId = " + pgOrderId + "] 주문을 찾을 수 없습니다.");
    }
}
