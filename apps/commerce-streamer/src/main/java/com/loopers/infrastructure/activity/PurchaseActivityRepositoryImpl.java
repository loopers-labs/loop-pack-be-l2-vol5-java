package com.loopers.infrastructure.activity;

import com.loopers.domain.activity.ProcessedOrderConfirmationModel;
import com.loopers.domain.activity.ProductPurchaseActivityModel;
import com.loopers.domain.activity.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class PurchaseActivityRepositoryImpl implements PurchaseActivityRepository {
    private final ProcessedOrderConfirmationJpaRepository processedOrderConfirmationJpaRepository;
    private final ProductPurchaseActivityJpaRepository productPurchaseActivityJpaRepository;

    @Override
    public List<ProcessedOrderConfirmationModel> findConfirmations(String eventId, Long orderId) {
        return processedOrderConfirmationJpaRepository.findByEventIdOrOrderId(eventId, orderId);
    }

    @Override
    public void saveConfirmation(ProcessedOrderConfirmationModel confirmation) {
        processedOrderConfirmationJpaRepository.saveAndFlush(confirmation);
    }

    @Override
    public void saveActivity(ProductPurchaseActivityModel activity) {
        productPurchaseActivityJpaRepository.saveAndFlush(activity);
    }

    @Override
    public List<ProductPurchaseActivityModel> findActivitiesByOrderId(Long orderId) {
        return productPurchaseActivityJpaRepository.findByOrderIdOrderByProductIdAsc(orderId);
    }
}
