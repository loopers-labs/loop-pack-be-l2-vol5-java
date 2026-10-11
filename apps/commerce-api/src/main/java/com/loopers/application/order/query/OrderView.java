package com.loopers.application.order.query;

import java.time.ZonedDateTime;
import java.util.List;

/** 주문 조회 전용 DTO 묶음. 조회 하나당 중첩 record 하나. 엔티티를 담지 않는다 (DR-31). */
public final class OrderView {
    private OrderView() {
    }

    /**
     * FR-ORDER-03/04, FR-ADMIN-ORDER-01/02 주문 한 건. 설계 4-3-0 OrderResponse / AdminOrderResponse 의 원천.
     * userId 는 소유 판정(FR-ORDER-04)과 관리자 응답(구매자)에 쓴다. paidAmount·confirmedAt 은 DRAFT 면 null.
     */
    public record Detail(
        Long id,
        Long userId,
        String status,
        Long totalAmount,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        List<Item> items,
        ZonedDateTime createdAt
    ) {
    }

    /** 설계 4-3-0 OrderItemResponse. 항목 금액 = 단가 × 수량 (저장하지 않는다, DR-13). */
    public record Item(Long productId, Integer quantity, Long unitPrice, Long lineAmount) {
    }

    /** FR-ADMIN-ORDER-01 구매자별 묶음 (ASM-19). 설계 4-3-0 AdminOrderGroup. 묶음 안 주문은 최신순. */
    public record BuyerGroup(Long userId, List<Detail> orders) {
    }
}
