package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {

    private final BrandJpaRepository brandJpaRepository;

    @Override
    public Optional<BrandModel> findActive(Long brandId) {
        return brandJpaRepository.findByIdAndDeletedAtIsNull(brandId);
    }

    @Override
    public BrandModel save(BrandModel brand) {
        return brandJpaRepository.save(brand);
    }

    @Override
    public PageResult<BrandModel> findActivePage(PageCommand page, ListSort sort) {
        Sort.Direction direction = sort.isDescending() ? Sort.Direction.DESC : Sort.Direction.ASC;
        Page<BrandModel> found = brandJpaRepository.findAllByDeletedAtIsNull(
            PageRequest.of(page.page(), page.size(), Sort.by(direction, "createdAt", "id")));
        return PageResult.of(found.getContent(), page, found.getTotalElements());
    }
}
