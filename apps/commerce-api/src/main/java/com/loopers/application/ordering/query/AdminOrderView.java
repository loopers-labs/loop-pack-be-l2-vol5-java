package com.loopers.application.ordering.query;

import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.application.pay.query.PaymentAttemptView;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.result.OrderResult;
import java.time.Instant;
import java.util.List;

// 관리자용 주문 조회 뷰
public record AdminOrderView(long orderId, long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                             OrderRecordStatus paymentStatus, Instant createdAt, List<OrderItemView> items,
                             PaymentMethod paymentMethod, PaymentAttemptView paymentAttempt, String paymentLookupUrl,
                             boolean attentionRequired) {
    public static AdminOrderView from(ConfirmOrderResult result) {
        OrderResult order = result.order();
        return new AdminOrderView(order.orderId(), order.userId(), order.status(), order.totalAmount(),
            result.paymentAmount(), result.paymentStatus(), order.createdAt(),
            order.items().stream().map(OrderItemView::from).toList(), result.paymentMethod(), result.paymentAttempt(),
            "/api-admin/v1/orders/" + order.orderId(), result.attentionRequired());
    }
}
