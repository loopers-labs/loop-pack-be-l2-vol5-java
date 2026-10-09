package com.loopers.infrastructure.persistence.mall.entity;

import com.loopers.domain.mall.model.Brand;
import org.springframework.stereotype.Component;

@Component
// 브랜드 도메인·엔티티 변환기
public class BrandEntityMapper {
    // 엔티티를 도메인 모델로 변환
    public Brand toDomain(BrandJpaEntity entity) {
        return Brand.restore(entity.getId(), entity.getName(), entity.getDescription(), entity.isDeleted(), entity.getCreatedAt());
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
