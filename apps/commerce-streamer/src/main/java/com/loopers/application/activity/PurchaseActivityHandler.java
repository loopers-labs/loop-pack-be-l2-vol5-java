package com.loopers.application.activity;

import com.loopers.domain.activity.ProcessedOrderConfirmation;
import com.loopers.domain.activity.ProcessedOrderConfirmationRepository;
import com.loopers.domain.activity.ProductPurchaseActivity;
import com.loopers.domain.activity.ProductPurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Component
public class PurchaseActivityHandler {

    private final ProcessedOrderConfirmationRepository processedRepo;
    private final ProductPurchaseActivityRepository activityRepo;

    @Transactional
    public void consume(OrderConfirmedEventDto event) {
        String fingerprint = event.computeFingerprint();

        Optional<ProcessedOrderConfirmation> existing = processedRepo.findByEventId(event.eventId());
        if (existing.isPresent()) {
            if (!existing.get().getPayloadFingerprint().equals(fingerprint)) {
                throw new PayloadConflictException(
                    "같은 eventId에 다른 payload가 수신됨: eventId=" + event.eventId()
                );
            }
            log.info("중복 이벤트 no-op: eventId={}", event.eventId());
            return;
        }

        Optional<ProcessedOrderConfirmation> byOrder = processedRepo.findByOrderId(event.orderId());
        if (byOrder.isPresent()) {
            throw new PayloadConflictException(
                "같은 orderId에 다른 eventId가 수신됨: orderId=" + event.orderId()
                    + ", existingEventId=" + byOrder.get().getEventId()
                    + ", incomingEventId=" + event.eventId()
            );
        }

        processedRepo.save(new ProcessedOrderConfirmation(event.eventId(), event.orderId(), fingerprint));

        event.items().forEach(item ->
            activityRepo.save(new ProductPurchaseActivity(
                event.orderId(), item.productId(), item.quantity(), event.confirmedAt()
            ))
        );

        log.info("구매 활동 저장 완료: eventId={}, orderId={}, items={}",
            event.eventId(), event.orderId(), event.items().size());
    }
}
