package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class BrandRepositoryImpl implements BrandRepository {

    private final BrandJpaRepository brandJpaRepository;

    @Override
    public Brand save(Brand brand) {
        if (brand.getId() == null) {
            return brandJpaRepository.save(BrandEntity.from(brand)).toDomain();
        }
        BrandEntity entity = brandJpaRepository.findById(brand.getId())
            .orElseThrow(() -> new IllegalStateException("저장할 브랜드 행이 없습니다: id=" + brand.getId()));
        entity.apply(brand);
        return entity.toDomain();
    }

    @Override
    public Optional<Brand> findById(Long id) {
        return brandJpaRepository.findByIdAndDeletedAtIsNull(id).map(BrandEntity::toDomain);
    }

    @Override
    public Optional<Brand> findByIdForShare(Long id) {
        return brandJpaRepository.findAliveByIdForShare(id).map(BrandEntity::toDomain);
    }

    @Override
    public Optional<Brand> findByIdForUpdate(Long id) {
        return brandJpaRepository.findAliveByIdForUpdate(id).map(BrandEntity::toDomain);
    }

    @Override
    public List<Brand> findPage(int offset, int limit) {
        PageRequest pageRequest = PageRequest.of(offset / limit, limit);
        return brandJpaRepository.findByDeletedAtIsNullOrderByIdDesc(pageRequest).stream()
            .map(BrandEntity::toDomain).toList();
    }
}
