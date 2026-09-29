package com.loopers.application.order;

import com.loopers.domain.order.OrderQuantity;

import java.util.List;

public record OrderCreateCommand(Long userId, List<Line> lines) {

    public OrderCreateCommand {
        if (userId == null) {
            throw new IllegalArgumentException("userId 는 필수입니다.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("주문 품목은 1개 이상이어야 합니다.");
        }
        lines = List.copyOf(lines);
    }

    public record Line(Long productId, OrderQuantity quantity) {
        public Line {
            if (productId == null) {
                throw new IllegalArgumentException("productId 는 필수입니다.");
            }
            if (quantity == null) {
                throw new IllegalArgumentException("quantity 는 필수입니다.");
            }
        }
    }
}
