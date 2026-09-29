package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.loopers.infrastructure.brand.QBrandEntity.brandEntity;

@Repository
@RequiredArgsConstructor
public class BrandRepositoryImpl implements BrandRepository {

    private final BrandJpaRepository brandJpaRepository;
    private final JPAQueryFactory queryFactory;

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
    public PageWindow<Brand> findPage(PageNumber page, PageSize size) {
        List<Brand> rows = queryFactory.selectFrom(brandEntity)
            .where(brandEntity.deletedAt.isNull())
            .orderBy(brandEntity.id.desc())
            .offset(page.offsetWith(size))
            .limit(PageWindow.limitOf(size))
            .fetch().stream()
            .map(BrandEntity::toDomain).toList();
        return PageWindow.of(rows, size);
    }
}
