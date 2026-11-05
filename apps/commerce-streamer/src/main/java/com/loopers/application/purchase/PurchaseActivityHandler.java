package com.loopers.application.purchase;

import com.loopers.application.purchase.port.DuplicateConfirmationException;
import com.loopers.application.purchase.port.OrderEventAnomalyRepository;
import com.loopers.application.purchase.port.OrderEventAnomalyRepository.Kind;
import com.loopers.application.purchase.port.PurchaseActivityRepository;
import com.loopers.application.purchase.port.PurchaseActivityRepository.ProcessedConfirmation;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// 유일 제약 위반 뒤 같은 Bean 안에서 새 트랜잭션을 열어야 해서 @Transactional 대신 TransactionTemplate으로 경계를 연다.
@Component
public class PurchaseActivityHandler {

    private final PurchaseActivityRepository purchaseActivityRepository;
    private final OrderEventAnomalyRepository orderEventAnomalyRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PurchaseActivityHandler(
        PurchaseActivityRepository purchaseActivityRepository,
        OrderEventAnomalyRepository orderEventAnomalyRepository,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        this.purchaseActivityRepository = purchaseActivityRepository;
        this.orderEventAnomalyRepository = orderEventAnomalyRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public ConsumeResult consume(OrderConfirmedEvent event, EventSource source) {
        try {
            return transactionTemplate.execute(status -> consumeInTransaction(event, source));
        } catch (DuplicateConfirmationException e) {
            // 조회 뒤 다른 소비가 먼저 commit했다. 실패한 트랜잭션은 rollback됐으므로 새 트랜잭션에서 기존 기록과 다시 비교한다.
            return transactionTemplate.execute(status -> consumeInTransaction(event, source));
        }
    }

    public void recordInvalid(Kind kind, String eventId, Long orderId, EventSource source, String detail) {
        transactionTemplate.executeWithoutResult(status ->
            orderEventAnomalyRepository.record(kind, eventId, orderId, source, detail, clock.instant()));
    }

    private ConsumeResult consumeInTransaction(OrderConfirmedEvent event, EventSource source) {
        String fingerprint = event.fingerprint();
        Optional<ProcessedConfirmation> sameEvent = purchaseActivityRepository.findConfirmationByEventId(event.eventId());
        if (sameEvent.isPresent()) {
            if (sameEvent.get().fingerprint().equals(fingerprint)) {
                return ConsumeResult.ALREADY_PROCESSED;
            }
            recordConflict(Kind.CONFLICT_SAME_EVENT_ID, event, source, "같은 eventId의 기존 기록과 내용이 다름: 기존 orderId="
                + sameEvent.get().orderId() + ", 기존 지문=" + sameEvent.get().fingerprint() + ", 새 지문=" + fingerprint);
            return ConsumeResult.CONFLICT_SAME_EVENT_ID;
        }
        Optional<ProcessedConfirmation> sameOrder = purchaseActivityRepository.findConfirmationByOrderId(event.orderId());
        if (sameOrder.isPresent()) {
            recordConflict(Kind.CONFLICT_SAME_ORDER, event, source,
                "같은 orderId가 다른 eventId로 이미 처리됨: 기존 eventId=" + sameOrder.get().eventId());
            return ConsumeResult.CONFLICT_SAME_ORDER;
        }

        Instant now = clock.instant();
        purchaseActivityRepository.saveConfirmation(event, now);
        for (PurchasedItem item : event.items()) {
            purchaseActivityRepository.saveActivity(event.orderId(), item, event.confirmedAt(), now);
        }
        return ConsumeResult.RECORDED;
    }

    private void recordConflict(Kind kind, OrderConfirmedEvent event, EventSource source, String detail) {
        orderEventAnomalyRepository.record(kind, event.eventId(), event.orderId(), source, detail, clock.instant());
    }
}
