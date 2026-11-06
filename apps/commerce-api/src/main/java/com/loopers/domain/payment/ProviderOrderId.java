package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 내부 주문 id를 PG가 보는 주문 번호로 바꾼다. 내부 주문 42 → {@code LP-ORD-0000000042}.
 * 생성·조회·알림에 같은 문자열을 써야 같은 거래를 찾으므로, 변환 규칙을 이 한곳에만 둔다.
 */
public final class ProviderOrderId {

    private static final String FORMAT = "LP-ORD-%010d";

    private ProviderOrderId() {
    }

    public static String from(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 id는 0보다 커야 합니다.");
        }
        return String.format(FORMAT, orderId);
    }
}
