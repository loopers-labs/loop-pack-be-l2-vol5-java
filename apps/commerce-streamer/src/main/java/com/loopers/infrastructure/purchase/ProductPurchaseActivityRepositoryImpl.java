package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductPurchaseActivityRepositoryImpl implements ProductPurchaseActivityRepository {

    private final ProductPurchaseActivityJpaRepository productPurchaseActivityJpaRepository;

    /** saveAndFlush — unique 판정을 commit까지 미루지 않고 이 호출에서 받는다. */
    @Override
    public ProductPurchaseActivity insert(ProductPurchaseActivity entity) {
        return productPurchaseActivityJpaRepository.saveAndFlush(entity);
    }

    @Override
    public List<ProductPurchaseActivity> findAllByOrderId(Long orderId) {
        return productPurchaseActivityJpaRepository.findAllByOrderIdOrderByProductIdAsc(orderId);
    }
}
