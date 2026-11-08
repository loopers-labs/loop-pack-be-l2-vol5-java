package com.loopers.application.order.dto;

import com.loopers.domain.order.enums.OrderBackfillResult;

/** 한 주문의 실패가 다른 주문의 복구를 막지 않도록 주문별로 결과 또는 오류를 담음 */
public record OrderBackfillInfo(Long orderId, OrderBackfillResult result, String error) {

    public static OrderBackfillInfo of(Long orderId, OrderBackfillResult result) {
        return new OrderBackfillInfo(orderId, result, null);
    }

    public static OrderBackfillInfo failed(Long orderId, String error) {
        return new OrderBackfillInfo(orderId, null, error);
    }
}
