package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
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
    public int updateActiveDetails(Long productId, String name, long price, ZonedDateTime updatedAt) {
        return productJpaRepository.updateActiveDetails(productId, name, price, updatedAt);
    }

    @Override
    public int updateActiveStock(Long productId, long stock, ZonedDateTime updatedAt) {
        return productJpaRepository.updateActiveStock(productId, stock, updatedAt);
    }

    @Override
    public int softDeleteActiveById(Long productId, ZonedDateTime deletedAt) {
        return productJpaRepository.softDeleteActiveById(productId, deletedAt);
    }

    @Override
    public int softDeleteActiveByBrandId(Long brandId, ZonedDateTime deletedAt) {
        return productJpaRepository.softDeleteActiveByBrandId(brandId, deletedAt);
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
