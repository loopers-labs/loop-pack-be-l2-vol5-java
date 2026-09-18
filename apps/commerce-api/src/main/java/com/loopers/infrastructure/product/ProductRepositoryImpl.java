package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository repository;

    public ProductRepositoryImpl(ProductJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Product save(Product product) {
        return repository.saveAndFlush(product);
    }

    @Override
    public Optional<Product> findById(long productId) {
        return repository.findById(productId);
    }

    @Override
    public Optional<Product> lockById(long productId) {
        return repository.lockById(productId);
    }

    @Override
    public Optional<Long> findBrandId(long productId) {
        return repository.findBrandId(productId);
    }

    @Override
    public boolean existsNonDeletedByBrandId(long brandId) {
        return repository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }
}
