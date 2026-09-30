package com.loopers.infrastructure.persistence.mall.entity;

import com.loopers.domain.mall.model.Product;
import org.springframework.stereotype.Component;

@Component
// 상품 도메인·엔티티 변환기
public class ProductEntityMapper {
    // 엔티티를 도메인 모델로 변환
    public Product toDomain(ProductJpaEntity entity) {
        return Product.restore(entity.getId(), entity.getBrandId(), entity.getName(), entity.getDescription(),
            entity.getPrice(), entity.getStock(), entity.isDeleted(), entity.getCreatedAt());
    }

    // 신규 저장용 엔티티 생성
    public ProductJpaEntity toNewEntity(Product product) {
        return new ProductJpaEntity(product.getBrandId(), product.getName(), product.getDescription(),
            product.getPrice(), product.getStock(), product.isDeleted());
    }

    // 도메인 값을 기존 엔티티에 반영
    public void apply(Product product, ProductJpaEntity entity) {
        entity.apply(product.getBrandId(), product.getName(), product.getDescription(), product.getPrice(),
            product.getStock(), product.isDeleted());
    }
}
