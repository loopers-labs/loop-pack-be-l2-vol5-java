package com.loopers.infrastructure.query.mall;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.mall.query.BrandQueryDao;
import com.loopers.application.mall.query.BrandView;
import com.loopers.infrastructure.persistence.mall.entity.QBrandJpaEntity;
import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// QueryDSL 기반 브랜드 조회 DAO
public class QueryDslBrandQueryDao implements BrandQueryDao {
    private static final QBrandJpaEntity BRAND = QBrandJpaEntity.brandJpaEntity;

    private final JPAQueryFactory queryFactory;

    // 단건 브랜드 조회
    @Override
    @Transactional(readOnly = true)
    public Optional<BrandView> findById(long brandId) {
        BrandView view = queryFactory.select(brandProjection())
            .from(BRAND)
            .where(BRAND.id.eq(brandId), BRAND.deleted.isFalse())
            .fetchOne();
        return Optional.ofNullable(view);
    }

    // 브랜드 페이지 목록 조회
    @Override
    @Transactional(readOnly = true)
    public PageResult<BrandView> findAll(PageCriteria criteria) {
        Long count = queryFactory.select(BRAND.count())
            .from(BRAND)
            .where(BRAND.deleted.isFalse())
            .fetchOne();
        List<BrandView> items = queryFactory.select(brandProjection())
            .from(BRAND)
            .where(BRAND.deleted.isFalse())
            .orderBy(BRAND.createdAt.desc(), BRAND.id.desc())
            .offset(criteria.offset())
            .limit(criteria.size())
            .fetch();
        return PageResult.of(items, criteria.page(), criteria.size(), count == null ? 0L : count);
    }

    private ConstructorExpression<BrandView> brandProjection() {
        return Projections.constructor(BrandView.class, BRAND.id, BRAND.name, BRAND.description);
    }
}
