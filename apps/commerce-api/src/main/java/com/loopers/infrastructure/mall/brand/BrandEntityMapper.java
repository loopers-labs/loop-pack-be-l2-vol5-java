package com.loopers.infrastructure.mall.brand;

import com.loopers.domain.mall.brand.Brand;
import com.loopers.infrastructure.mall.product.ProductEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
// 브랜드 도메인·엔티티 변환기
public class BrandEntityMapper {
    private final ProductEntityMapper productEntityMapper;

    // 엔티티를 도메인 모델로 변환
    public Brand toDomain(BrandJpaEntity entity) {
        return Brand.restore(entity.getId(), entity.getName(), entity.getDescription(), entity.isDeleted(), entity.getCreatedAt());
    }

    // 삭제 전용 조회 엔티티를 소속 상품과 함께 도메인 모델로 변환
    public Brand toDomainForDeletion(BrandJpaEntity entity) {
        return Brand.restoreForDeletion(entity.getId(), entity.getName(), entity.getDescription(), entity.isDeleted(),
            entity.getCreatedAt(), entity.getProducts().stream().map(productEntityMapper::toDomain).toList());
    }

    // 신규 저장용 엔티티 생성
    public BrandJpaEntity toNewEntity(Brand brand) {
        return new BrandJpaEntity(brand.getName(), brand.getDescription(), brand.isDeleted());
    }

    // 도메인 값을 기존 엔티티에 반영
    public void apply(Brand brand, BrandJpaEntity entity) {
        entity.apply(brand.getName(), brand.getDescription(), brand.isDeleted());
    }
}
