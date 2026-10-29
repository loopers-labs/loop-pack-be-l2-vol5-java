package com.loopers.infrastructure.purchase.persistence;

import com.loopers.domain.purchase.entity.ProductPurchaseActivity;
import com.loopers.domain.purchase.repository.ProductPurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductPurchaseActivityRepositoryImpl implements ProductPurchaseActivityRepository {
    private final ProductPurchaseActivityJpaRepository productPurchaseActivityJpaRepository;

    @Override
    public ProductPurchaseActivity save(ProductPurchaseActivity activity) {
        return productPurchaseActivityJpaRepository.save(activity);
    }

    @Override
    public List<ProductPurchaseActivity> findAllByOrderId(Long orderId) {
        return productPurchaseActivityJpaRepository.findAllByOrderId(orderId);
    }
}
