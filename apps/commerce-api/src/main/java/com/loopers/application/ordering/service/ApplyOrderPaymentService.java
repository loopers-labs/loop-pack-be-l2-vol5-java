package com.loopers.application.ordering.service;

import com.loopers.application.mall.service.ProductService;
import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.support.PaymentApplicationTransaction;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplyOrderPaymentService {
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final StockAdjustmentRepository adjustments;
    private final PaymentRecoveryTaskRepository tasks;
    private final ProductService products;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ApplyOrderPaymentResult> apply(String key) {
        return apply(key, new PaymentApplicationTransaction());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ApplyOrderPaymentResult> apply(String key, PaymentApplicationTransaction transaction) {
        transaction.register();
        PaymentAttempt attempt = attempts.findByKeyForUpdate(key)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
        Order order = order(attempt);
        PaymentRecoveryTask task = task(key);
        if (completed(order, task)) {
            return Optional.of(result(ApplyOrderPaymentResult.State.APPLIED, attempt));
        }
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING || task.getState() == PaymentRecoveryTask.State.APPLIED) {
            throw new IllegalStateException("주문과 복구 작업의 업무 반영 상태가 일치하지 않습니다.");
        }
        if (attempt.getConflict() != PaymentAttempt.Conflict.NONE) {
            return Optional.of(result(ApplyOrderPaymentResult.State.CONFLICT, attempt));
        }
        if (attempt.getOutcome() == PaymentAttempt.Outcome.UNKNOWN || attempt.getOutcome() == PaymentAttempt.Outcome.PENDING) {
            return Optional.of(result(ApplyOrderPaymentResult.State.PENDING, attempt));
        }
        if (attempt.getDispatchState() != PaymentAttempt.DispatchState.STARTED || attempt.getAmount() != order.getTotalAmount()) {
            throw new IllegalStateException("발송한 결제 시도와 주문 금액이 일치해야 합니다.");
        }
        boolean success = attempt.getOutcome() == PaymentAttempt.Outcome.SUCCESS;
        OrderStatus target = success ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED;
        OrderRecord record = success ? OrderRecord.paidByPg(order.getUserId(), attempt.getAmount()) : null;
        if (!orders.completePgIfPending(order.getId(), target, record)) {
            return Optional.empty();
        }
        StockAdjustment adjustment = adjustments.findByOrderId(order.getId())
            .orElseThrow(() -> new IllegalStateException("주문의 재고 차감 기록이 없습니다."));
        if (adjustment.getState() != StockAdjustment.State.DEDUCTED) {
            throw new IllegalStateException("미완료 주문의 재고 차감 기록이 이미 종결됐습니다.");
        }
        if (success) {
            adjustment.consume();
        } else {
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            adjustment.getItems().forEach(item -> quantities.put(item.productId(), item.quantity()));
            products.restoreStocks(quantities);
            adjustment.restore();
        }
        adjustments.save(adjustment);
        if (!tasks.markApplied(key, Instant.now())) {
            throw new IllegalStateException("복구 작업의 업무 반영 완료를 저장할 수 없습니다.");
        }
        return Optional.of(result(ApplyOrderPaymentResult.State.APPLIED, attempt));
    }

    // CAS 경합/업무 실패 뒤 새 TX에서 완료 여부를 우선 판정한다. 옛 실패가 완료 응답을 덮지 않는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ApplyOrderPaymentResult currentResult(String key, boolean applicationFailed) {
        PaymentAttempt attempt = attempts.findByKey(key)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
        if (completed(order(attempt), task(key))) {
            return result(ApplyOrderPaymentResult.State.APPLIED, attempt);
        }
        if (applicationFailed) {
            return result(ApplyOrderPaymentResult.State.APPLICATION_FAILED, attempt);
        }
        return result(attempt.getConflict() == PaymentAttempt.Conflict.NONE
            ? ApplyOrderPaymentResult.State.PENDING : ApplyOrderPaymentResult.State.CONFLICT, attempt);
    }

    private Order order(PaymentAttempt attempt) {
        return orders.findById(attempt.getOrderId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));
    }

    private PaymentRecoveryTask task(String key) {
        return tasks.findByKey(key).orElseThrow(() -> new IllegalStateException("결제 복구 작업이 없습니다."));
    }

    private boolean completed(Order order, PaymentRecoveryTask task) {
        return (order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.PAYMENT_FAILED)
            && task.getState() == PaymentRecoveryTask.State.APPLIED;
    }

    private ApplyOrderPaymentResult result(ApplyOrderPaymentResult.State state, PaymentAttempt attempt) {
        return new ApplyOrderPaymentResult(state, attempt.getConflict() != PaymentAttempt.Conflict.NONE
            || state == ApplyOrderPaymentResult.State.CONFLICT || state == ApplyOrderPaymentResult.State.APPLICATION_FAILED);
    }
}
