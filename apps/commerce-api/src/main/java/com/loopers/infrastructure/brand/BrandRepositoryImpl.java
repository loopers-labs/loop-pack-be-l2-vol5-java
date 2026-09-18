package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class BrandRepositoryImpl implements BrandRepository {
    private final BrandJpaRepository repository;
    @Override
    @Transactional(readOnly = true)
    public Optional<Brand> findById(long id) { return repository.findById(id).map(BrandJpaEntity::toDomain); }
    @Override
    public Brand save(Brand brand) {
        BrandJpaEntity entity = brand.getId() == null ? new BrandJpaEntity(brand) : repository.findById(brand.getId()).orElseThrow();
        entity.update(brand);
        return repository.save(entity).toDomain();
    }
    @Override
    @Transactional(readOnly = true)
    public Page<Brand> findAll(Pageable pageable) { return repository.findAll(pageable).map(BrandJpaEntity::toDomain); }
}
