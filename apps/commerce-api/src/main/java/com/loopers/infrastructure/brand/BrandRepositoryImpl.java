package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {

    private final BrandJpaRepository brandJpaRepository;

    @Override
    public boolean existsByName(String name) {
        return brandJpaRepository.existsByName(name);
    }

    @Override
    public boolean existsByNameAndIdNot(String name, Long brandId) {
        return brandJpaRepository.existsByNameAndIdNot(name, brandId);
    }

    @Override
    public Optional<Brand> findById(Long brandId) {
        return brandJpaRepository.findById(brandId).map(BrandJpaMapper::toDomain);
    }

    @Override
    public Optional<Brand> findActiveById(Long brandId) {
        return brandJpaRepository.findByIdAndDeletedAtIsNull(brandId).map(BrandJpaMapper::toDomain);
    }

    @Override
    public List<Brand> findAll() {
        return map(brandJpaRepository.findAll());
    }

    @Override
    public List<Brand> findAllActive() {
        return map(brandJpaRepository.findAllByDeletedAtIsNull());
    }

    @Override
    public List<Brand> findAllDeleted() {
        return map(brandJpaRepository.findAllByDeletedAtIsNotNull());
    }

    @Override
    public List<Brand> findAllByIds(List<Long> brandIds) {
        return map(brandJpaRepository.findAllById(brandIds));
    }

    @Override
    public Brand save(Brand brand) {
        BrandJpaEntity entity = brand.getId() == null
            ? BrandJpaMapper.toNewEntity(brand)
            : brandJpaRepository.findById(brand.getId()).orElseThrow(
                () -> new IllegalArgumentException("Brand does not exist: " + brand.getId())
            );
        if (brand.getId() != null) {
            BrandJpaMapper.update(brand, entity);
        }
        return BrandJpaMapper.toDomain(brandJpaRepository.save(entity));
    }

    private List<Brand> map(List<BrandJpaEntity> entities) {
        return entities.stream().map(BrandJpaMapper::toDomain).toList();
    }
}
