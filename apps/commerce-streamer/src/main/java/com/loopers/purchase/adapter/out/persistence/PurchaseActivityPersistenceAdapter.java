package com.loopers.purchase.adapter.out.persistence;

import com.loopers.purchase.application.port.out.PurchaseActivityPort;
import com.loopers.purchase.domain.ConsumedOrderConfirmation;
import com.loopers.purchase.domain.OrderConfirmedMessage;
import com.loopers.purchase.domain.ProductPurchaseActivity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PurchaseActivityPersistenceAdapter implements PurchaseActivityPort {

    private final ConsumedOrderConfirmationJpaRepository consumedRepository;
    private final ProductPurchaseActivityJpaRepository activityRepository;

    @Override
    public Optional<ConsumedOrderConfirmation> findByEventIdOrOrderId(String eventId, Long orderId) {
        List<ConsumedOrderConfirmation> found = consumedRepository.findByEventIdOrOrderId(eventId, orderId);
        // 같은 eventId 행을 먼저 본다 — 같은 eventId·다른 주문과 같은 주문·다른 eventId가 함께 걸릴 수 있다
        return found.stream().min(Comparator.comparing(row -> row.getEventId().equals(eventId) ? 0 : 1));
    }

    @Override
    public void saveAll(OrderConfirmedMessage message, String payload) {
        consumedRepository.saveAndFlush(ConsumedOrderConfirmation.of(message, payload));
        for (OrderConfirmedMessage.Item item : message.normalizedItems()) {
            activityRepository.saveAndFlush(ProductPurchaseActivity.of(message, item));
        }
    }
}
