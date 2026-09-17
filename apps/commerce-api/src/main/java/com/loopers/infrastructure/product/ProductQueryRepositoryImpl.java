package com.loopers.infrastructure.product;

import com.loopers.domain.brand.QBrandModel;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.like.QLikeModel;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.QProductModel;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final QProductModel PRODUCT = QProductModel.productModel;
    private static final QBrandModel BRAND = QBrandModel.brandModel;
    private static final QLikeModel LIKE = QLikeModel.likeModel;
    private static final QLikeModel COUNTED_LIKE = new QLikeModel("countedLike");

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<ProductQueryResult> findDetail(Long productId) {
        return Optional.ofNullable(
            baseQuery().where(PRODUCT.id.eq(productId)).fetchOne()
        );
    }

    @Override
    public PageResult<ProductQueryResult> findPage(Long brandId, PageCommand page, ProductSort sort) {
        BooleanExpression brandFilter = brandId != null ? PRODUCT.brandId.eq(brandId) : null;

        List<ProductQueryResult> items = baseQuery()
            .where(brandFilter)
            .orderBy(orderOf(sort))
            .offset((long) page.page() * page.size())
            .limit(page.size())
            .fetch();

        Long total = queryFactory.select(PRODUCT.count())
            .from(PRODUCT)
            .join(BRAND).on(BRAND.id.eq(PRODUCT.brandId).and(BRAND.deletedAt.isNull()))
            .where(PRODUCT.deletedAt.isNull(), brandFilter)
            .fetchOne();

        return PageResult.of(items, page, total != null ? total : 0L);
    }

    @Override
    public PageResult<ProductQueryResult> findAllPage(PageCommand page, ListSort sort) {
        List<ProductQueryResult> items = baseQuery()
            .orderBy(orderOf(sort))
            .offset((long) page.page() * page.size())
            .limit(page.size())
            .fetch();

        Long total = queryFactory.select(PRODUCT.count())
            .from(PRODUCT)
            .join(BRAND).on(BRAND.id.eq(PRODUCT.brandId).and(BRAND.deletedAt.isNull()))
            .where(PRODUCT.deletedAt.isNull())
            .fetchOne();

        return PageResult.of(items, page, total != null ? total : 0L);
    }

    @Override
    public PageResult<ProductQueryResult> findLikedPage(Long userId, PageCommand page, ListSort sort) {
        OrderSpecifier<?>[] order = sort.isDescending()
            ? new OrderSpecifier<?>[]{LIKE.createdAt.desc(), LIKE.id.desc()}
            : new OrderSpecifier<?>[]{LIKE.createdAt.asc(), LIKE.id.asc()};

        List<ProductQueryResult> items = baseQuery()
            .join(LIKE).on(LIKE.productId.eq(PRODUCT.id).and(LIKE.userId.eq(userId)))
            .orderBy(order)
            .offset((long) page.page() * page.size())
            .limit(page.size())
            .fetch();

        Long total = queryFactory.select(PRODUCT.count())
            .from(PRODUCT)
            .join(BRAND).on(BRAND.id.eq(PRODUCT.brandId).and(BRAND.deletedAt.isNull()))
            .join(LIKE).on(LIKE.productId.eq(PRODUCT.id).and(LIKE.userId.eq(userId)))
            .where(PRODUCT.deletedAt.isNull())
            .fetchOne();

        return PageResult.of(items, page, total != null ? total : 0L);
    }

    /** 상품·브랜드 조인과 좋아요 집계를 한 번의 읽기 전용 쿼리로 처리한다. */
    private JPAQuery<ProductQueryResult> baseQuery() {
        return queryFactory
            .select(Projections.constructor(
                ProductQueryResult.class,
                PRODUCT.id,
                PRODUCT.brandId,
                BRAND.name,
                PRODUCT.name,
                PRODUCT.price.won,
                likeCount(),
                PRODUCT.stock.quantity
            ))
            .from(PRODUCT)
            .join(BRAND).on(BRAND.id.eq(PRODUCT.brandId).and(BRAND.deletedAt.isNull()))
            .where(PRODUCT.deletedAt.isNull());
    }

    private Expression<Long> likeCount() {
        return JPAExpressions.select(COUNTED_LIKE.count())
            .from(COUNTED_LIKE)
            .where(COUNTED_LIKE.productId.eq(PRODUCT.id));
    }

    private OrderSpecifier<?>[] orderOf(ListSort sort) {
        return sort.isDescending()
            ? new OrderSpecifier<?>[]{PRODUCT.createdAt.desc(), PRODUCT.id.desc()}
            : new OrderSpecifier<?>[]{PRODUCT.createdAt.asc(), PRODUCT.id.asc()};
    }

    private OrderSpecifier<?>[] orderOf(ProductSort sort) {
        return switch (sort) {
            case PRICE_ASC -> new OrderSpecifier<?>[]{PRODUCT.price.won.asc(), PRODUCT.id.asc()};
            case LIKES_DESC -> new OrderSpecifier<?>[]{
                new OrderSpecifier<>(Order.DESC, likeCount()), PRODUCT.id.desc()};
            case LATEST -> new OrderSpecifier<?>[]{PRODUCT.createdAt.desc(), PRODUCT.id.desc()};
        };
    }
}
