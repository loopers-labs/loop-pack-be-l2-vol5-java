package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.payment.entity.Payment;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * 고객 주문 상세. 품목마다 판매 여부(상품이 지금 살아 있는가)를 담음 (설계 6.4, D-42).
 * 생성 · 확정 응답과 관리자 상세에는 판매 여부가 없으므로 OrderInfo 와 따로 둠.
 */
public record OrderDetailInfo(
    Long id,
    String status,
    long totalAmount,
    Long paymentAmount,
    ZonedDateTime paidAt,
    ZonedDateTime orderedAt,
    List<Item> items,
    String paymentMethod,
    String paymentStatus
) {
    public record Item(Long productId, String productName, long unitPrice, int quantity, long amount, boolean onSale) {
        static Item of(OrderItem item, boolean onSale) {
            return new Item(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getAmount(), onSale);
        }
    }

    /** 품목의 상품 식별자가 activeProductIds 에 없으면 삭제된 상품이라 판매종료로 봄 */
    /** payment 는 결제 시도가 없으면 null */
    public static OrderDetailInfo of(Order order, Set<Long> activeProductIds, Payment payment) {
        return new OrderDetailInfo(
            order.getId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getPaidAt(),
            order.getCreatedAt(),
            order.getItems().stream()
                .map(item -> Item.of(item, activeProductIds.contains(item.getProductId())))
                .toList(),
            order.getPaymentMethod() == null ? null : order.getPaymentMethod().name(),
            payment == null ? null : payment.getStatus().name()
        );
    }
}
