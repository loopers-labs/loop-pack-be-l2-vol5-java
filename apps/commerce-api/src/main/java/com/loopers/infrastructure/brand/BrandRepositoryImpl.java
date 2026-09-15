package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {

    private final BrandJpaRepository brandJpaRepository;

    /**
     * 즉시 flush해 @PrePersist·@PreUpdate(생성·수정 시각)가 응답을 만들기 전에 반영되게 한다.
     */
    @Override
    public BrandModel save(BrandModel brand) {
        return brandJpaRepository.saveAndFlush(brand);
    }

    @Override
    public Optional<BrandModel> findActiveById(Long id) {
        return brandJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public Page<BrandModel> findActive(Pageable pageable) {
        return brandJpaRepository.findAllByDeletedAtIsNull(pageable);
    }

    @Override
    public List<BrandModel> findAllByIds(Collection<Long> ids) {
        return brandJpaRepository.findAllById(ids);
    }
}
