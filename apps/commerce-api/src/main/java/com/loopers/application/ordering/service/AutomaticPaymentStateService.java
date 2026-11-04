package com.loopers.application.ordering.service;

import com.loopers.application.ordering.result.AutomaticPaymentState;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AutomaticPaymentStateService {
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentRecoveryTaskRepository tasks;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentRecoveryTask.Snapshot> claim(String key, String executionId) {
        AutomaticPaymentState state = load(key);
        if (!state.lookupAllowed() || state.task().state() != PaymentRecoveryTask.State.READY
                || state.task().executionId() != null || state.task().usedRounds() != 0
                || !tasks.claim(key, executionId, Instant.now())) { return Optional.empty(); }
        return Optional.of(new PaymentRecoveryTask.Snapshot(key, PaymentRecoveryTask.State.RUNNING, executionId, 0));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AutomaticPaymentState read(String key) { return load(key); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(PaymentRecoveryTask.Snapshot expected) {
        AutomaticPaymentState state = load(expected.attemptKey());
        return state.lookupAllowed() && expected.equals(state.task()) && expected.state() == PaymentRecoveryTask.State.RUNNING
            && tasks.consumeRound(expected.attemptKey(), expected.executionId(), expected.usedRounds(), Instant.now());
    }

    private AutomaticPaymentState load(String key) {
        var attempt = attempts.findByKey(key).orElseThrow(() -> new IllegalStateException("자동 조회의 결제 시도가 없습니다."));
        var order = orders.findById(attempt.getOrderId()).orElseThrow(() -> new IllegalStateException("결제 주문이 없습니다."));
        var task = tasks.findByKey(key).orElseThrow(() -> new IllegalStateException("결제 복구 작업이 없습니다."));
        return new AutomaticPaymentState(order.getStatus(), attempt, task.snapshot());
    }
}
