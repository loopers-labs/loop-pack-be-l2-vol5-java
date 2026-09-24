package com.loopers.infrastructure.mall.product;

import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// 상품 레포지토리 JPA 구현체
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;
    private final ProductEntityMapper mapper;

    // 신규 또는 기존 상품 저장
    @Override
    public Product save(Product product) {
        ProductJpaEntity entity;
        if (product.getId() == null) {
            entity = mapper.toNewEntity(product);
        } else {
            entity = productJpaRepository.findById(product.getId()).orElseThrow();
            mapper.apply(product, entity);
        }
        return mapper.toDomain(productJpaRepository.save(entity));
    }

    @Override
    public Optional<Product> findById(long productId) {
        return productJpaRepository.findById(productId).map(mapper::toDomain);
    }

    // 비관적 쓰기 잠금으로 조회
    @Override
    public Optional<Product> findByIdForUpdate(long productId) {
        return productJpaRepository.findByIdForUpdate(productId).map(mapper::toDomain);
    }
}
