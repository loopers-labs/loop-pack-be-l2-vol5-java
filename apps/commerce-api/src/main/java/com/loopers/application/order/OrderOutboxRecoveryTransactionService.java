package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmedOutboxModel;
import com.loopers.domain.order.OrderConfirmedOutboxModel.Transition;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/**
 * 운영 진입점의 TX 경계(설계 W7.3-2·W7.3-7). 이번 단위는 BLOCKED 재개만 제공한다. 주문 복구(recoverOne)는 후속 단위다.
 */
@Component
public class OrderOutboxRecoveryTransactionService {

    private final OrderConfirmedOutboxRepository repository;

    public OrderOutboxRecoveryTransactionService(OrderConfirmedOutboxRepository repository) {
        this.repository = repository;
    }

    /** 행을 잠그고 BLOCKED 면 재개한다. PENDING·PUBLISHED 는 변경 없이 해당 결과다. 없는 eventId 는 오류다. */
    @Transactional
    public Transition resumeBlocked(String eventId, ZonedDateTime now) {
        OrderConfirmedOutboxModel outbox = repository.findForUpdateByEventId(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Outbox 행이 없는 eventId 입니다."));
        Transition transition = outbox.resume(now);
        if (transition == Transition.APPLIED) {
            repository.saveAndFlush(outbox);
        }
        return transition;
    }
}
