package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    @Override
    public Product save(Product product) {
        if (product.getId() == null) {
            return productJpaRepository.save(ProductEntity.from(product)).toDomain();
        }
        ProductEntity entity = productJpaRepository.findById(product.getId())
            .orElseThrow(() -> new IllegalStateException("저장할 상품 행이 없습니다: id=" + product.getId()));
        entity.apply(product);
        return entity.toDomain();
    }

    @Override
    public Optional<Product> findById(Long id) {
        return productJpaRepository.findByIdAndDeletedAtIsNull(id).map(ProductEntity::toDomain);
    }

    @Override
    public Optional<Product> findByIdForUpdate(Long id) {
        return productJpaRepository.findAliveByIdForUpdate(id).map(ProductEntity::toDomain);
    }

    @Override
    public boolean existsByBrandId(Long brandId) {
        return productJpaRepository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }
}
