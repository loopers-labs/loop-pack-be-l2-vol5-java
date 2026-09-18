package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    @Override
    public Optional<Product> findById(Long productId) {
        return productJpaRepository.findById(productId);
    }

    @Override
    public List<Product> findAll() {
        return productJpaRepository.findAll();
    }

    @Override
    public List<Product> findAllActive() {
        return productJpaRepository.findAllByDeletedAtIsNull();
    }

    @Override
    public List<Product> findAllDeleted() {
        return productJpaRepository.findAllByDeletedAtIsNotNull();
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        return productJpaRepository.existsByBrand_IdAndDeletedAtIsNull(brandId);
    }

    @Override
    public Product save(Product product) {
        return productJpaRepository.save(product);
    }
}
