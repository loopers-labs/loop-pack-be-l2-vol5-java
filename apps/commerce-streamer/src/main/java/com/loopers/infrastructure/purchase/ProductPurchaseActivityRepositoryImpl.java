package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductPurchaseActivityRepositoryImpl implements ProductPurchaseActivityRepository {
    private final ProductPurchaseActivityJpaRepository jpaRepository;

    @Override
    public List<ProductPurchaseActivity> saveAllAndFlush(List<ProductPurchaseActivity> activities) {
        return jpaRepository.saveAllAndFlush(activities);
    }

    @Override
    public List<ProductPurchaseActivity> findByOrderId(Long orderId) {
        return jpaRepository.findByOrderId(orderId);
    }
}
