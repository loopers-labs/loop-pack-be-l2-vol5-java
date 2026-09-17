package com.loopers.domain.order;

import java.util.List;

/** FR-ADMIN-ORDER-01 구매자별 묶음 (ASM-19). 묶음 안 주문은 최신순. */
public record BuyerOrders(Long userId, List<OrderModel> orders) {
}
