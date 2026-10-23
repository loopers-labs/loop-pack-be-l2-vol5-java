package com.loopers.application.ordering.service;

import com.loopers.application.ordering.command.HandlePaymentCallbackCommand;
import com.loopers.application.ordering.result.PaymentCallbackContext;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// GET 조회 API가 아닌 callback 쓰기 조율의 대상·기존 실행 확인이다. 실행 권한을 바꾸지 않는다.
public class ResolvePaymentCallbackService {
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final PaymentRecoveryTaskRepository tasks;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PaymentCallbackContext resolve(HandlePaymentCallbackCommand command) {
        List<PaymentAttempt> matches = attempts.findByProviderOrderId(command.providerOrderId());
        if (matches.isEmpty()) { throw new ApplicationException(ApplicationErrorCode.PAYMENT_ATTEMPT_NOT_FOUND); }
        if (matches.size() != 1) { throw new ApplicationException(ApplicationErrorCode.PAYMENT_CALLBACK_CONFLICT); }
        PaymentAttempt attempt = matches.getFirst();
        if (attempt.getTransactionKey() != null && !attempt.getTransactionKey().equals(command.transactionKey())) {
            throw new ApplicationException(ApplicationErrorCode.PAYMENT_CALLBACK_CONFLICT);
        }
        if (attempt.getDispatchState() != PaymentAttempt.DispatchState.STARTED) {
            throw new DomainException(DomainErrorCode.PAYMENT_OBSERVATION_NOT_ALLOWED);
        }
        Order order = orders.findById(attempt.getOrderId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));
        PaymentRecoveryTask task = tasks.findByKey(attempt.getAttemptKey())
            .orElseThrow(() -> new IllegalStateException("결제 복구 작업이 없습니다."));
        return new PaymentCallbackContext(attempt, order.getUserId(), task.snapshot());
    }
}
