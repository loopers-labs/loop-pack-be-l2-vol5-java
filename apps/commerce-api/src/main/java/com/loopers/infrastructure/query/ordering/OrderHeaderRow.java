package com.loopers.infrastructure.query.ordering;

import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderItemView;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import java.time.Instant;
import java.util.List;

// 주문 헤더 조회 결과 로우
public record OrderHeaderRow(long orderId, long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                      OrderRecordStatus paymentStatus, Instant createdAt) {
    // 일반 사용자 응답 뷰로 변환
    OrderView toView(List<OrderItemView> items) {
        return new OrderView(orderId, status, totalAmount, paymentAmount, paymentStatus, createdAt, items);
    }

    // 관리자 응답 뷰로 변환
    AdminOrderView toAdminView(List<OrderItemView> items) {
        return new AdminOrderView(orderId, userId, status, totalAmount, paymentAmount, paymentStatus, createdAt,
            items);
    }
}
