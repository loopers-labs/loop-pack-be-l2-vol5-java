package com.loopers.infrastructure.persistence.mall.repository;

import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.infrastructure.persistence.mall.entity.BrandEntityMapper;
import com.loopers.infrastructure.persistence.mall.entity.BrandJpaEntity;
import com.loopers.infrastructure.persistence.mall.entity.ProductEntityMapper;
import com.loopers.infrastructure.persistence.mall.entity.ProductJpaEntity;
import com.loopers.infrastructure.persistence.mall.jpa.BrandJpaRepository;
import com.loopers.infrastructure.persistence.mall.jpa.ProductJpaRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// 브랜드 레포지토리 JPA 구현체
public class BrandRepositoryImpl implements BrandRepository {
    private final BrandJpaRepository brandJpaRepository;
    private final BrandEntityMapper mapper;
    private final ProductJpaRepository productJpaRepository;
    private final ProductEntityMapper productEntityMapper;

    // 신규 또는 기존 브랜드 저장. 기존 브랜드는 연결 상품의 변경도 함께 반영한다.
    @Override
    public Brand save(Brand brand) {
        BrandJpaEntity entity;
        if (brand.getId() == null) {
            entity = mapper.toNewEntity(brand);
        } else {
            entity = brandJpaRepository.findById(brand.getId()).orElseThrow();
            mapper.apply(brand, entity);
            for (Product product : brand.getProducts()) {
                ProductJpaEntity productEntity = productJpaRepository.findById(product.getId()).orElseThrow();
                productEntityMapper.apply(product, productEntity);
                productJpaRepository.saveAndFlush(productEntity);
            }
        }
        return mapper.toDomain(brandJpaRepository.saveAndFlush(entity));
    }

    @Override
    public Optional<Brand> findById(long brandId) {
        return brandJpaRepository.findById(brandId).map(mapper::toDomain);
    }

    @Override
    public Optional<Brand> findForDeletion(long brandId) {
        return brandJpaRepository.findForDeletion(brandId).map(mapper::toDomainForDeletion);
    }
}
