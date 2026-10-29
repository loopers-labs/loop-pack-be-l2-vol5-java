package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PurchaseActivityRepositoryImpl implements PurchaseActivityRepository {
    private final ProcessedOrderConfirmationJpaRepository processedOrderConfirmationJpaRepository;
    private final ProductPurchaseActivityJpaRepository productPurchaseActivityJpaRepository;

    @Override
    public Optional<ProcessedOrderConfirmation> findProcessedByEventId(String eventId) {
        return processedOrderConfirmationJpaRepository.findByEventId(eventId);
    }

    @Override
    public Optional<ProcessedOrderConfirmation> findProcessedByOrderId(Long orderId) {
        return processedOrderConfirmationJpaRepository.findByOrderId(orderId);
    }

    @Override
    public ProcessedOrderConfirmation saveProcessed(ProcessedOrderConfirmation confirmation) {
        return processedOrderConfirmationJpaRepository.save(confirmation);
    }

    @Override
    public ProductPurchaseActivity saveActivity(ProductPurchaseActivity activity) {
        return productPurchaseActivityJpaRepository.save(activity);
    }

    @Override
    public List<ProductPurchaseActivity> findActivitiesByOrderId(Long orderId) {
        return productPurchaseActivityJpaRepository.findAllByOrderIdOrderByProductIdAsc(orderId);
    }
}
