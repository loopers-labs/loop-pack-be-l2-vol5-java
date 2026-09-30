package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;

import java.time.ZonedDateTime;
import java.util.List;

// 고객·관리자 조회가 공유하는 Info — 필드 노출 범위는 interfaces 계층의 응답 DTO가 결정한다
// (docs/week2/design.md 1번 섹션, 7번 섹션 참고). Order는 고객·관리자 응답 필드 차이가 계약표에
// 없어서 하나로 둔다.
public record OrderInfo(
    Long id,
    Long userId,
    OrderStatus status,
    List<Item> items,
    Long paidAmount,
    ZonedDateTime confirmedAt,
    ZonedDateTime createdAt
) {
    public static OrderInfo from(Order order) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getItems().stream()
                .map(item -> new Item(item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .toList(),
            order.getPaidAmount(),
            order.getConfirmedAt(),
            order.getCreatedAt()
        );
    }

    public record Item(Long productId, int quantity, Long unitPrice) {}
}
