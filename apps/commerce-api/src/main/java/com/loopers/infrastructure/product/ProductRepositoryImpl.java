package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
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
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<ProductModel> findActiveById(Long id) {
        return productJpaRepository.findById(id)
            .filter(product -> product.getDeletedAt() == null);
    }

    @Override
    public Optional<ProductModel> findById(Long id) {
        return productJpaRepository.findById(id);
    }

    @Override
    public Page<ProductModel> findAll(Pageable pageable) {
        return productJpaRepository.findAll(pageable);
    }

    @Override
    public Page<ProductModel> findAllActive(Pageable pageable) {
        return productJpaRepository.findByDeletedAtIsNull(pageable);
    }

    @Override
    public Page<ProductModel> findAllActiveByBrandId(Long brandId, Pageable pageable) {
        return productJpaRepository.findByBrandIdAndDeletedAtIsNull(brandId, pageable);
    }

    @Override
    public List<ProductModel> findAllActiveByIds(List<Long> ids) {
        return productJpaRepository.findByIdInAndDeletedAtIsNull(ids);
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        return productJpaRepository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }
}
