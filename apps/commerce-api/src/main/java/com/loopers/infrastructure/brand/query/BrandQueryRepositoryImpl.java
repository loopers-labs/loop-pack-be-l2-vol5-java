package com.loopers.infrastructure.brand.query;

import com.loopers.application.brand.query.BrandQueryRepository;
import com.loopers.application.brand.query.BrandView;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.loopers.domain.brand.QBrandModel.brandModel;

@RequiredArgsConstructor
@Component
public class BrandQueryRepositoryImpl implements BrandQueryRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<BrandView.Summary> findActive(Long brandId) {
        if (brandId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(queryFactory
            .select(Projections.constructor(BrandView.Summary.class, brandModel.id, brandModel.name))
            .from(brandModel)
            .where(brandModel.id.eq(brandId), brandModel.deletedAt.isNull())
            .fetchOne());
    }

    /** 설계 3-7-A FR-ADMIN-BRAND-01: created_at desc, id desc. */
    @Override
    public PageResult<BrandView.Admin> findPage(PageQuery query) {
        List<BrandView.Admin> items = queryFactory.select(admin())
            .from(brandModel)
            .orderBy(brandModel.createdAt.desc(), brandModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        Long total = queryFactory.select(brandModel.count())
            .from(brandModel)
            .fetchOne();
        return PageResult.of(items, query, total == null ? 0 : total);
    }

    @Override
    public Optional<BrandView.Admin> find(Long brandId) {
        if (brandId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(queryFactory.select(admin())
            .from(brandModel)
            .where(brandModel.id.eq(brandId))
            .fetchOne());
    }

    private static Expression<BrandView.Admin> admin() {
        return Projections.constructor(BrandView.Admin.class,
            brandModel.id, brandModel.name, new CaseBuilder().when(brandModel.deletedAt.isNotNull()).then(true).otherwise(false));
    }
}
