package com.loopers.application.ordering.service;

import com.loopers.application.ordering.command.ReconcileOrderPaymentCommand;
import com.loopers.application.ordering.result.PrepareOrderReconciliationResult;
import com.loopers.application.ordering.support.OrderAdminAccess;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PrepareOrderReconciliationService {
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentRecoveryTaskRepository tasks;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PrepareOrderReconciliationResult prepare(ReconcileOrderPaymentCommand command) {
        if (command.administrator()) { OrderAdminAccess.requireAdmin(command.userId()); }
        Order order = orders.findById(command.orderId())
            .filter(found -> command.administrator() || found.getUserId() == command.userId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));
        PaymentAttempt attempt = attempts.findByOrderId(order.getId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
        PaymentRecoveryTask task = tasks.findByKey(attempt.getAttemptKey())
            .orElseThrow(() -> new IllegalStateException("결제 복구 작업이 없습니다."));
        if ((order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.PAYMENT_FAILED)
                && task.getState() == PaymentRecoveryTask.State.APPLIED) {
            return new PrepareOrderReconciliationResult(order.getUserId(), attempt, null);
        }
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING || task.getState() == PaymentRecoveryTask.State.APPLIED) {
            throw new IllegalStateException("주문과 복구 작업의 상태가 일치하지 않습니다.");
        }
        String executionId = UUID.randomUUID().toString();
        boolean claimed = task.getState() == PaymentRecoveryTask.State.READY
            ? tasks.claim(attempt.getAttemptKey(), executionId, Instant.now())
            : tasks.handover(attempt.getAttemptKey(), task.getState(), task.getExecutionId(), task.getUsedRounds(),
                executionId, Instant.now());
        return new PrepareOrderReconciliationResult(order.getUserId(), attempt, claimed
            ? new PaymentRecoveryTask.Snapshot(attempt.getAttemptKey(), PaymentRecoveryTask.State.RUNNING,
                executionId, task.getUsedRounds()) : null);
    }
}
