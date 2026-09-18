package com.loopers.interfaces.api.order;

import com.loopers.domain.order.OrderStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Arrays;

/** 외부 계약의 주문 상태 필터 값을 바꾼다. 모르는 값은 기본값으로 바꾸지 않고 거절한다 (설계 6.1). */
public final class OrderStatusParam {

    private OrderStatusParam() {}

    /** 값이 없으면 null (거르지 않음). */
    public static OrderStatus toStatus(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(OrderStatus.values())
            .filter(status -> status.name().equals(value))
            .findFirst()
            .orElseThrow(() -> new CoreException(ErrorType.BAD_REQUEST, "status 는 DRAFT, CONFIRMED 중 하나여야 합니다."));
    }
}
