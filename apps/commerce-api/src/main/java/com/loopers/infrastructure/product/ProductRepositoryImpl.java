package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    @Override
    public Optional<ProductModel> find(Long id) {
        return productJpaRepository.findById(id);
    }

    @Override
    public Optional<ProductModel> findForUpdate(Long id) {
        return productJpaRepository.findForUpdate(id);
    }

    @Override
    public List<ProductModel> findAllActive() {
        return productJpaRepository.findAllByDeletedAtIsNull();
    }

    @Override
    public List<ProductModel> findAllByIds(List<Long> ids) {
        return productJpaRepository.findAllById(ids);
    }

    @Override
    public List<ProductModel> findAllActiveByBrandIdForUpdate(Long brandId) {
        return productJpaRepository.findAllActiveByBrandIdForUpdate(brandId);
    }

    @Override
    public Page<ProductModel> findActive(Long brandId, ProductSort sort, Pageable pageable) {
        return switch (sort) {
            case LATEST -> productJpaRepository.findActiveOrderByLatest(brandId, pageable);
            case PRICE_ASC -> productJpaRepository.findActiveOrderByPriceAsc(brandId, pageable);
            case LIKES_DESC -> productJpaRepository.findActiveOrderByLikesDesc(brandId, pageable);
        };
    }

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }
}
