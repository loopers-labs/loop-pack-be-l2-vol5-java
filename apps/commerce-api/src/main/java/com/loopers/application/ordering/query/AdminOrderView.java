package com.loopers.application.ordering.query;

import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import java.time.Instant;
import java.util.List;

// 관리자용 주문 조회 뷰
public record AdminOrderView(long orderId, long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                             OrderRecordStatus paymentStatus, Instant createdAt, List<OrderItemView> items) {}
