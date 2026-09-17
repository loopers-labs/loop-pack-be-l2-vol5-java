package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
    public boolean existsActiveByBrandId(Long brandId) {
        return productJpaRepository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }
}
