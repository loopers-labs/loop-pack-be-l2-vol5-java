package com.loopers.infrastructure.mall.brand;

import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.infrastructure.mall.product.ProductEntityMapper;
import com.loopers.infrastructure.mall.product.ProductJpaEntity;
import com.loopers.infrastructure.mall.product.ProductJpaRepository;
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
