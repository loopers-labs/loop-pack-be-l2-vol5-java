package com.loopers.infrastructure.brand;

import com.loopers.application.brand.port.BrandRepository;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class BrandPersistenceAdapter implements BrandRepository {
    private final BrandJpaRepository jpaRepository;

    public BrandPersistenceAdapter(BrandJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public Brand save(Brand brand) {
        BrandJpaEntity entity;
        if (brand.getId() == null) {
            entity = new BrandJpaEntity(brand.getName(), brand.isDeleted());
        } else {
            entity = jpaRepository.findById(brand.getId().value())
                .orElseThrow(() -> new IllegalStateException("저장할 브랜드가 존재하지 않습니다."));
            entity.update(brand.getName(), brand.isDeleted());
        }
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Brand> findById(BrandId id) {
        return jpaRepository.findById(id.value()).map(this::toDomain);
    }

    @Override
    @Transactional
    public Optional<Brand> findByIdForUpdate(BrandId id) {
        return jpaRepository.findForUpdate(id.value()).map(this::toDomain);
    }

    @Override
    public java.util.List<Brand> findPage(int page, int size) {
        return jpaRepository.findAll(org.springframework.data.domain.PageRequest.of(page, size,
            org.springframework.data.domain.Sort.by("id").descending())).stream().map(this::toDomain).toList();
    }

    @Override
    public java.util.List<Brand> findAllByIds(java.util.Collection<BrandId> ids) {
        return jpaRepository.findAllById(ids.stream().map(BrandId::value).toList()).stream().map(this::toDomain).toList();
    }

    private Brand toDomain(BrandJpaEntity entity) {
        return Brand.restore(new BrandId(entity.getId()), entity.getName(), entity.isDeleted());
    }
}
