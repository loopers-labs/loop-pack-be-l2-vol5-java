package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderItemInfo;
import com.loopers.application.order.dto.OrderSummaryInfo;
import com.loopers.application.order.dto.PaymentInfo;

import java.time.ZonedDateTime;
import java.util.List;

public class AdminOrderDto {

    // 구매자 id(userId)를 포함한다. payment 는 확정된 주문에만 있고, 확정 전(DRAFT)에는 null 이다.
    public record OrderResponse(
        Long id,
        Long userId,
        String status,
        List<ItemResponse> items,
        long totalAmount,
        PaymentResponse payment,
        ZonedDateTime orderedAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status().name(),
                info.items().stream().map(ItemResponse::from).toList(),
                info.totalAmount(),
                PaymentResponse.from(info.payment()),
                info.orderedAt()
            );
        }
    }

    // 목록의 항목. 품목은 담지 않고 개수만 담는다. 품목은 상세에서 본다.
    public record OrderSummaryResponse(
        Long id,
        Long userId,
        String status,
        long totalAmount,
        int itemCount,
        PaymentResponse payment,
        ZonedDateTime orderedAt
    ) {
        public static OrderSummaryResponse from(OrderSummaryInfo info) {
            return new OrderSummaryResponse(
                info.id(),
                info.userId(),
                info.status().name(),
                info.totalAmount(),
                info.itemCount(),
                PaymentResponse.from(info.payment()),
                info.orderedAt()
            );
        }
    }

    // 상품명과 단가는 주문 당시 값이다.
    public record ItemResponse(Long productId, String productName, int quantity, long unitPrice, long amount) {
        public static ItemResponse from(OrderItemInfo info) {
            return new ItemResponse(
                info.productId(), info.productName(), info.quantity(), info.unitPrice(), info.amount()
            );
        }
    }

    public record PaymentResponse(long paidAmount) {
        // 확정 전(DRAFT)에는 결제 결과가 없으므로 null 을 받으면 null 을 돌려준다. null 필드는 응답에서 생략된다.
        public static PaymentResponse from(PaymentInfo info) {
            if (info == null) {
                return null;
            }
            return new PaymentResponse(info.paidAmount());
        }
    }
}
