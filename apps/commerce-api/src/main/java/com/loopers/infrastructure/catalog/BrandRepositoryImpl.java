package com.loopers.infrastructure.catalog;

import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.catalog.BrandRepository;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.catalog.QBrandModel.brandModel;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {
    private final BrandJpaRepository brandJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public BrandModel save(BrandModel brand) {
        return brandJpaRepository.save(brand);
    }

    @Override
    public Optional<BrandModel> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return brandJpaRepository.findById(id);
    }

    @Override
    public List<BrandModel> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return brandJpaRepository.findAllById(ids);
    }

    @Override
    public PageResult<BrandModel> findPage(PageQuery query) {
        List<BrandModel> items = queryFactory.selectFrom(brandModel)
            .orderBy(brandModel.createdAt.desc(), brandModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        long total = brandJpaRepository.count();
        return PageResult.of(items, query, total);
    }
}
