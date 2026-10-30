package com.loopers.purchase.application;

import com.loopers.purchase.application.event.OrderConfirmedEvent;
import com.loopers.purchase.domain.ProcessedOrderConfirmation;
import com.loopers.purchase.domain.ProcessedOrderConfirmationRepository;
import com.loopers.purchase.domain.ProductPurchaseActivity;
import com.loopers.purchase.domain.ProductPurchaseActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class PurchaseActivityHandler {

    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final int SCHEMA_VERSION = 1;

    private final ProcessedOrderConfirmationRepository processedOrderConfirmationRepository;
    private final ProductPurchaseActivityRepository productPurchaseActivityRepository;

    public PurchaseActivityHandler(
        ProcessedOrderConfirmationRepository processedOrderConfirmationRepository,
        ProductPurchaseActivityRepository productPurchaseActivityRepository
    ) {
        this.processedOrderConfirmationRepository = processedOrderConfirmationRepository;
        this.productPurchaseActivityRepository = productPurchaseActivityRepository;
    }

    @Transactional
    public void consume(OrderConfirmedEvent event) {
        validate(event);

        Optional<ProcessedOrderConfirmation> byEventId =
            processedOrderConfirmationRepository.findById(event.eventId());
        if (byEventId.isPresent()) {
            assertSameFact(event, byEventId.get());
            return;
        }

        Optional<ProcessedOrderConfirmation> byOrderId =
            processedOrderConfirmationRepository.findByOrderId(event.orderId());
        if (byOrderId.isPresent()) {
            assertSameFact(event, byOrderId.get());
            return;
        }

        processedOrderConfirmationRepository.saveAndFlush(new ProcessedOrderConfirmation(
            event.eventId(), event.orderId(), event.confirmedAt()));
        productPurchaseActivityRepository.saveAll(event.items().stream()
            .map(item -> new ProductPurchaseActivity(
                event.orderId(), item.productId(), item.quantity(), event.confirmedAt()))
            .toList());
        productPurchaseActivityRepository.flush();
    }

    private void validate(OrderConfirmedEvent event) {
        if (event == null
            || !EVENT_TYPE.equals(event.eventType())
            || event.schemaVersion() != SCHEMA_VERSION
            || event.eventId() == null
            || event.eventId().isBlank()
            || event.orderId() == null
            || event.orderId() <= 0
            || event.confirmedAt() == null
            || event.items() == null
            || event.items().isEmpty()) {
            throw new IllegalArgumentException("Invalid OrderConfirmed event");
        }

        if (event.items().stream().anyMatch(item -> item == null
            || item.productId() == null
            || item.productId() <= 0
            || item.quantity() <= 0)) {
            throw new IllegalArgumentException("Invalid OrderConfirmed items");
        }

        long distinctProductCount = event.items().stream()
            .map(OrderConfirmedEvent.Item::productId)
            .distinct()
            .count();
        if (distinctProductCount != event.items().size()) {
            throw new IllegalArgumentException("OrderConfirmed items contain duplicate products");
        }
    }

    private void assertSameFact(OrderConfirmedEvent event, ProcessedOrderConfirmation existing) {
        if (!event.eventId().equals(existing.getEventId())
            || !event.orderId().equals(existing.getOrderId())
            || !event.confirmedAt().equals(existing.getConfirmedAt())
            || !sameItems(event, existing.getOrderId())) {
            throw new IllegalStateException("Conflicting OrderConfirmed event: " + event.eventId());
        }
    }

    private boolean sameItems(OrderConfirmedEvent event, Long orderId) {
        List<ProductPurchaseActivity> existingActivities =
            productPurchaseActivityRepository.findAllById_OrderIdOrderById_ProductId(orderId);
        Map<Long, Integer> existingItems = existingActivities.stream()
            .collect(Collectors.toMap(
                activity -> activity.getId().getProductId(),
                ProductPurchaseActivity::getQuantity
            ));
        Map<Long, Integer> incomingItems = event.items().stream()
            .collect(Collectors.toMap(OrderConfirmedEvent.Item::productId, OrderConfirmedEvent.Item::quantity));
        return existingItems.equals(incomingItems)
            && existingActivities.stream().allMatch(activity -> event.confirmedAt().equals(activity.getConfirmedAt()));
    }
}
