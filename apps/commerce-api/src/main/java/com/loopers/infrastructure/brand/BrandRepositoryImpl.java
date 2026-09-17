package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.brand.QBrand;
import com.loopers.domain.common.PageCondition;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {
    private static final QBrand brand = QBrand.brand;

    private final BrandJpaRepository brandJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<Brand> findActive(Long id) {
        return brandJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public List<Brand> findAll(Collection<Long> ids) {
        return brandJpaRepository.findAllById(ids);
    }

    @Override
    public List<Brand> findActive(PageCondition page) {
        return queryFactory
            .selectFrom(brand)
            .where(brand.deletedAt.isNull())
            .orderBy(brand.createdAt.desc(), brand.id.desc())
            .offset(page.offset())
            .limit(page.size())
            .fetch();
    }

    @Override
    public long countActive() {
        return brandJpaRepository.countByDeletedAtIsNull();
    }

    @Override
    public Brand save(Brand brand) {
        return brandJpaRepository.save(brand);
    }
}
