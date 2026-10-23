package com.loopers.infrastructure.query.ordering;

import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderItemView;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.application.pay.query.PaymentAttemptView;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.time.Instant;
import java.util.List;

// 주문 헤더 조회 결과 로우
public record OrderHeaderRow(long orderId, long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                      OrderRecordStatus paymentStatus, Instant createdAt, PaymentMethod paymentMethod,
                      PaymentAttemptRow attempt) {
    // 일반 사용자 응답 뷰로 변환
    OrderView toView(List<OrderItemView> items) {
        return new OrderView(orderId, status, totalAmount, paymentAmount, paymentStatus, createdAt, items,
            resolvedMethod(), paymentAttempt(), lookupUrl("/api/v1/orders/"), attentionRequired());
    }

    // 관리자 응답 뷰로 변환
    AdminOrderView toAdminView(List<OrderItemView> items) {
        return new AdminOrderView(orderId, userId, status, totalAmount, paymentAmount, paymentStatus, createdAt,
            items, resolvedMethod(), paymentAttempt(), lookupUrl("/api-admin/v1/orders/"), attentionRequired());
    }

    private PaymentAttemptView paymentAttempt() { return attempt == null ? null : attempt.toView(); }

    private PaymentMethod resolvedMethod() {
        return paymentMethod != null ? paymentMethod : paymentAttempt() == null ? null : PaymentMethod.PG;
    }

    private String lookupUrl(String path) {
        return paymentAttempt() == null ? null : path + orderId;
    }

    private boolean attentionRequired() {
        PaymentAttemptView view = paymentAttempt();
        return view != null && (view.conflict() != PaymentAttempt.Conflict.NONE
            || view.recoveryState() == PaymentRecoveryTask.State.MANUAL_REQUIRED);
    }
}
