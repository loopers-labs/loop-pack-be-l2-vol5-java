package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PurchaseActivityRepositoryImpl implements PurchaseActivityRepository {
    private final ProductPurchaseActivityJpaRepository activities;

    @Override
    public void save(ProductPurchaseActivity activity) {
        activities.save(activity);
    }
}
