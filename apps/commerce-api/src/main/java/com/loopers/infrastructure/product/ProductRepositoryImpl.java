package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    @Override
    public Optional<ProductModel> findActive(Long productId) {
        return productJpaRepository.findByIdAndDeletedAtIsNull(productId);
    }

    @Override
    public List<ProductModel> findAllActiveByIds(Collection<Long> productIds) {
        return productJpaRepository.findAllByIdInAndDeletedAtIsNull(productIds);
    }

    @Override
    public Optional<ProductModel> findActiveForUpdate(Long productId) {
        return productJpaRepository.findActiveForUpdateById(productId);
    }

    @Override
    public List<ProductModel> findAllActiveByIdsForUpdate(Collection<Long> productIds) {
        return productJpaRepository.findAllActiveForUpdateByIdIn(productIds);
    }

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }

    @Override
    public int softDeleteAllActiveByBrandId(Long brandId, ZonedDateTime deletedAt) {
        return productJpaRepository.softDeleteAllActiveByBrandId(brandId, deletedAt);
    }
}
