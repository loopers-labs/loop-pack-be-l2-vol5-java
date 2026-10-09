package com.loopers.infrastructure.persistence.mall.repository;

import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.infrastructure.persistence.mall.entity.BrandEntityMapper;
import com.loopers.infrastructure.persistence.mall.entity.BrandJpaEntity;
import com.loopers.infrastructure.persistence.mall.jpa.BrandJpaRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// 브랜드 레포지토리 JPA 구현체
public class BrandRepositoryImpl implements BrandRepository {
    private final BrandJpaRepository brandJpaRepository;
    private final BrandEntityMapper mapper;

    // 신규 또는 기존 브랜드 저장
    @Override
    public Brand save(Brand brand) {
        BrandJpaEntity entity;
        if (brand.getId() == null) {
            entity = mapper.toNewEntity(brand);
        } else {
            entity = brandJpaRepository.findById(brand.getId()).orElseThrow();
            mapper.apply(brand, entity);
        }
        return mapper.toDomain(brandJpaRepository.saveAndFlush(entity));
    }

    @Override
    public Optional<Brand> findById(long brandId) {
        return brandJpaRepository.findById(brandId).map(mapper::toDomain);
    }

    // 비관적 쓰기 잠금으로 조회
    @Override
    public Optional<Brand> findByIdForUpdate(long brandId) {
        return brandJpaRepository.findByIdForUpdate(brandId).map(mapper::toDomain);
    }

    // 비관적 공유 잠금으로 조회
    @Override
    public Optional<Brand> findByIdForShare(long brandId) {
        return brandJpaRepository.findByIdForShare(brandId).map(mapper::toDomain);
    }
}
