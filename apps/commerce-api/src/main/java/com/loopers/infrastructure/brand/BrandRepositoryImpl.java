package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {
    private final BrandJpaRepository brandJpaRepository;

    @Override
    public BrandModel save(BrandModel brand) {
        return brandJpaRepository.save(brand);
    }

    @Override
    public Optional<BrandModel> find(Long id) {
        return brandJpaRepository.findById(id);
    }

    @Override
    public Optional<BrandModel> findForShare(Long id) {
        return brandJpaRepository.findForShare(id);
    }

    @Override
    public Optional<BrandModel> findForUpdate(Long id) {
        return brandJpaRepository.findForUpdate(id);
    }

    @Override
    public List<BrandModel> findActiveByIds(Collection<Long> ids) {
        return brandJpaRepository.findByIdInAndDeletedAtIsNull(ids);
    }

    @Override
    public List<BrandModel> findAllActive() {
        return brandJpaRepository.findByDeletedAtIsNull();
    }

    @Override
    public boolean existsActiveByNameIgnoreCase(String name) {
        return brandJpaRepository.existsByNameIgnoreCaseAndDeletedAtIsNull(name);
    }
}
