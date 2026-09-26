package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;
    private final BrandJpaRepository brandJpaRepository;

    @Override
    public Optional<Product> findById(Long productId) {
        return productJpaRepository.findById(productId).map(ProductJpaMapper::toDomain);
    }

    @Override
    public List<Product> findAll() {
        return productJpaRepository.findAll().stream().map(ProductJpaMapper::toDomain).toList();
    }

    @Override
    public List<Product> findAllActive() {
        return productJpaRepository.findAllByDeletedAtIsNull().stream().map(ProductJpaMapper::toDomain).toList();
    }

    @Override
    public List<Product> findAllDeleted() {
        return productJpaRepository.findAllByDeletedAtIsNotNull().stream().map(ProductJpaMapper::toDomain).toList();
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        return productJpaRepository.existsByBrand_IdAndDeletedAtIsNull(brandId);
    }

    @Override
    public Product save(Product product) {
        ProductJpaEntity entity;
        if (product.getId() == null) {
            var brand = brandJpaRepository.getReferenceById(product.getBrandId());
            entity = ProductJpaMapper.toNewEntity(product, brand);
        } else {
            entity = productJpaRepository.findById(product.getId()).orElseThrow(
                () -> new IllegalArgumentException("Product does not exist: " + product.getId())
            );
            ProductJpaMapper.update(product, entity);
        }
        return ProductJpaMapper.toDomain(productJpaRepository.save(entity));
    }
}
