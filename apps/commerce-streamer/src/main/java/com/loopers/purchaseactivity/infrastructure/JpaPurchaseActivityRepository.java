package com.loopers.purchaseactivity.infrastructure;

import com.loopers.purchaseactivity.domain.PurchaseActivity;
import com.loopers.purchaseactivity.domain.PurchaseActivityRepository;

import org.springframework.stereotype.Repository;

@Repository
public class JpaPurchaseActivityRepository implements PurchaseActivityRepository {
    private final ProductPurchaseActivityJpaRepository repository;

    public JpaPurchaseActivityRepository(ProductPurchaseActivityJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(PurchaseActivity activity) {
        repository.saveAndFlush(new ProductPurchaseActivityEntity(activity));
    }
}
