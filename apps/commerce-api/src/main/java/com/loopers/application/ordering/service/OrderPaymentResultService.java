package com.loopers.application.ordering.service;

import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.result.OrderResult;
import com.loopers.application.pay.query.PaymentAttemptView;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 쓰기 처리 뒤의 일관된 응답 snapshot. GET 조회 UseCase가 아니다. */
@Service
@RequiredArgsConstructor
public class OrderPaymentResultService {
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentRecoveryTaskRepository tasks;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ConfirmOrderResult read(long userId, long orderId, boolean attention) {
        Order order = orders.findById(orderId).filter(found -> found.getUserId() == userId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));
        OrderRecord record = order.getRecord().orElse(null);
        PaymentAttempt attempt = attempts.findByOrderId(orderId).orElseThrow();
        PaymentRecoveryTask task = tasks.findByKey(attempt.getAttemptKey()).orElseThrow();
        boolean completed = (order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.PAYMENT_FAILED)
            && task.getState() == PaymentRecoveryTask.State.APPLIED;
        return new ConfirmOrderResult(OrderResult.from(order), record == null ? null : record.getAmount(),
            record == null ? null : record.getStatus(), PaymentMethod.PG, PaymentAttemptView.from(attempt, task), completed,
            attention || attempt.getConflict() != PaymentAttempt.Conflict.NONE
                || task.getState() == PaymentRecoveryTask.State.MANUAL_REQUIRED);
    }
}
