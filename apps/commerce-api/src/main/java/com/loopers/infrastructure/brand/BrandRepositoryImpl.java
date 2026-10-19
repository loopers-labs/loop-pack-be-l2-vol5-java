package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.infrastructure.support.MySqlConstraintErrors;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
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
    public Optional<Brand> findActiveByIdWithExclusiveLock(Long brandId) {
        return brandJpaRepository.findActiveByIdWithExclusiveLock(brandId).map(BrandJpaMapper::toDomain);
    }

    @Override
    public Optional<Brand> findActiveByIdWithSharedLock(Long brandId) {
        return brandJpaRepository.findActiveByIdWithSharedLock(brandId).map(BrandJpaMapper::toDomain);
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
    public int updateActiveName(Long brandId, String name, ZonedDateTime updatedAt) {
        try {
            return brandJpaRepository.updateActiveName(brandId, name, updatedAt);
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicateName(exception);
        }
    }

    @Override
    public int softDeleteActiveById(Long brandId, ZonedDateTime deletedAt) {
        return brandJpaRepository.softDeleteActiveById(brandId, deletedAt);
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
        try {
            return BrandJpaMapper.toDomain(brandJpaRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicateName(exception);
        }
    }

    private RuntimeException translateDuplicateName(DataIntegrityViolationException exception) {
        return MySqlConstraintErrors.isDuplicateKey(exception, "uk_brand_name")
            ? new CoreException(ErrorType.CONFLICT, "이미 등록된 브랜드 이름입니다.")
            : exception;
    }

    private List<Brand> map(List<BrandJpaEntity> entities) {
        return entities.stream().map(BrandJpaMapper::toDomain).toList();
    }
}
