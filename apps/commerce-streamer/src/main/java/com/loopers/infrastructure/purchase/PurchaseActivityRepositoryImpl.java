package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PurchaseActivityRepositoryImpl implements PurchaseActivityRepository {

    private final ProcessedOrderConfirmationJpaRepository processedJpaRepository;
    private final ProductPurchaseActivityJpaRepository activityJpaRepository;

    @Override
    public Optional<ProcessedOrderConfirmation> findProcessedByEventId(String eventId) {
        return processedJpaRepository.findByEventId(eventId);
    }

    @Override
    public Optional<ProcessedOrderConfirmation> findProcessedByOrderId(Long orderId) {
        return processedJpaRepository.findByOrderId(orderId);
    }

    @Override
    public ProcessedOrderConfirmation saveProcessed(ProcessedOrderConfirmation processed) {
        return processedJpaRepository.saveAndFlush(processed);
    }

    @Override
    public ProductPurchaseActivity saveActivity(ProductPurchaseActivity activity) {
        return activityJpaRepository.save(activity);
    }
}
