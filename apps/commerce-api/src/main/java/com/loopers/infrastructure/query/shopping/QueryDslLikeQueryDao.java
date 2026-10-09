package com.loopers.infrastructure.query.shopping;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.mall.query.BrandSummaryView;
import com.loopers.application.shopping.query.LikeQueryDao;
import com.loopers.application.shopping.query.LikedProductView;
import com.loopers.infrastructure.persistence.mall.entity.QBrandJpaEntity;
import com.loopers.infrastructure.persistence.mall.entity.QProductJpaEntity;
import com.loopers.infrastructure.persistence.shopping.entity.QLikeJpaEntity;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// QueryDSL 기반 좋아요 목록 조회 DAO
public class QueryDslLikeQueryDao implements LikeQueryDao {
    private static final QLikeJpaEntity LIKE = QLikeJpaEntity.likeJpaEntity;
    private static final QProductJpaEntity PRODUCT = QProductJpaEntity.productJpaEntity;
    private static final QBrandJpaEntity BRAND = QBrandJpaEntity.brandJpaEntity;

    private final JPAQueryFactory queryFactory;

    // 사용자별 좋아요 목록 페이지 조회
    @Override
    @Transactional(readOnly = true)
    public PageResult<LikedProductView> findByUserId(long userId, PageCriteria criteria) {
        Long count = queryFactory.select(LIKE.count())
            .from(LIKE)
            .join(PRODUCT).on(PRODUCT.id.eq(LIKE.productId))
            .where(LIKE.userId.eq(userId), PRODUCT.deleted.isFalse())
            .fetchOne();

        List<LikedProductView> items = queryFactory.select(Projections.constructor(LikedProductView.class,
                PRODUCT.id, PRODUCT.name, PRODUCT.price,
                Projections.constructor(BrandSummaryView.class, BRAND.id, BRAND.name),
                PRODUCT.likeCount, LIKE.createdAt))
            .from(LIKE)
            .join(PRODUCT).on(PRODUCT.id.eq(LIKE.productId))
            .join(BRAND).on(BRAND.id.eq(PRODUCT.brandId))
            .where(LIKE.userId.eq(userId), PRODUCT.deleted.isFalse())
            .orderBy(LIKE.createdAt.desc(), LIKE.productId.desc())
            .offset(criteria.offset())
            .limit(criteria.size())
            .fetch();

        return PageResult.of(items, criteria.page(), criteria.size(), count == null ? 0L : count);
    }
}
