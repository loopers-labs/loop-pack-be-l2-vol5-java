package com.loopers.application.ordering.service;

import com.loopers.application.mall.service.ProductService;
import com.loopers.application.ordering.command.PrepareOrderPaymentCommand;
import com.loopers.application.ordering.result.PrepareOrderPaymentResult;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PrepareOrderPaymentService {
    private final OrderRepository orders;
    private final ProductService products;
    private final PaymentAttemptRepository attempts;
    private final StockAdjustmentRepository adjustments;
    private final PaymentRecoveryTaskRepository tasks;

    // 별도 빈의 proxy가 commit까지 마친 뒤에만 최초 발송 권한이 조율자에게 반환된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PrepareOrderPaymentResult> tryPrepare(PrepareOrderPaymentCommand command) {
        Order order = ownedOrder(command);
        Optional<PrepareOrderPaymentResult> existing = existing(order, command);
        if (existing.isPresent()) {
            return existing;
        }
        order.ensureCanConfirm();
        ensureKeyAvailable(command);
        if (!orders.prepareIfDraft(order.getId(), command.userId())) {
            return Optional.empty();
        }
        Map<Long, Integer> quantities = order.quantitiesByProductId();
        products.decreaseStocks(quantities);
        PaymentAttempt attempt = attempts.insert(PaymentAttempt.create(order.getId(), command.attemptKey(),
            order.getTotalAmount(), command.scenario()).startDispatch());
        adjustments.insert(StockAdjustment.create(order.getId(), quantities.entrySet().stream()
            .map(entry -> new StockAdjustment.Item(entry.getKey(), entry.getValue())).toList()));
        tasks.insert(PaymentRecoveryTask.create(attempt.getAttemptKey()));
        return Optional.of(new PrepareOrderPaymentResult(attempt, true));
    }

    // CAS 패자/유일 제약 실패자는 원래 TX가 끝난 뒤 새 snapshot에서만 기존 요청을 확인한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PrepareOrderPaymentResult> findExisting(PrepareOrderPaymentCommand command) {
        Order order = ownedOrder(command);
        Optional<PrepareOrderPaymentResult> existing = existing(order, command);
        if (existing.isPresent()) {
            return existing;
        }
        order.ensureCanConfirm();
        ensureKeyAvailable(command);
        return Optional.empty();
    }

    private Order ownedOrder(PrepareOrderPaymentCommand command) {
        return orders.findById(command.orderId()).filter(order -> order.getUserId() == command.userId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));
    }

    private Optional<PrepareOrderPaymentResult> existing(Order order, PrepareOrderPaymentCommand command) {
        return attempts.findByOrderId(order.getId()).map(attempt -> {
            if (!attempt.isSameRequest(order.getId(), command.attemptKey(), order.getTotalAmount(), command.scenario())) {
                throw new ApplicationException(ApplicationErrorCode.PAYMENT_REQUEST_CONFLICT);
            }
            return new PrepareOrderPaymentResult(attempt, false);
        });
    }

    private void ensureKeyAvailable(PrepareOrderPaymentCommand command) {
        if (attempts.findByKey(command.attemptKey()).isPresent()) {
            throw new ApplicationException(ApplicationErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
    }
}
