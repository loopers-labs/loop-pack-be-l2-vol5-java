package com.loopers.infrastructure.product.query;

import com.loopers.application.product.query.ProductQueryRepository;
import com.loopers.application.product.query.ProductView;
import com.loopers.domain.product.ProductSort;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.loopers.domain.brand.QBrandModel.brandModel;
import static com.loopers.domain.product.QProductModel.productModel;
import static com.loopers.domain.productlike.QProductLikeModel.productLikeModel;

/**
 * TB-03 에 TB-02·TB-04 를 조인+집계해 한 쿼리로 읽는다. 셋 다 BC-02 (DR-02, DR-31).
 * 상품의 브랜드는 소프트 삭제만 되므로 brand 는 inner join 이고, 삭제된 브랜드도 그대로 실린다.
 */
@RequiredArgsConstructor
@Component
public class ProductQueryRepositoryImpl implements ProductQueryRepository {
    private static final NumberExpression<Long> LIKE_COUNT = productLikeModel.id.count();

    private final JPAQueryFactory queryFactory;

    /** 설계 3-7-A: latest = created_at desc / price_asc = price asc / likes_desc = count(product_like) desc. 동률은 id desc (ASM-08). */
    @Override
    public PageResult<ProductView.Summary> findActivePage(ProductSort sort, PageQuery query) {
        OrderSpecifier<?> primary = switch (sort) {
            case LATEST -> productModel.createdAt.desc();
            case PRICE_ASC -> productModel.price.asc();
            case LIKES_DESC -> LIKE_COUNT.desc();
        };
        List<ProductView.Summary> items = selectJoined(summary())
            .where(productModel.deletedAt.isNull())
            .orderBy(primary, productModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        Long total = queryFactory.select(productModel.count())
            .from(productModel)
            .where(productModel.deletedAt.isNull())
            .fetchOne();
        return PageResult.of(items, query, total == null ? 0 : total);
    }

    @Override
    public Optional<ProductView.Summary> findActive(Long productId) {
        return Optional.ofNullable(selectJoined(summary())
            .where(productModel.id.eq(productId), productModel.deletedAt.isNull())
            .fetchOne());
    }

    @Override
    public PageResult<ProductView.Admin> findPage(PageQuery query) {
        List<ProductView.Admin> items = selectJoined(admin())
            .orderBy(productModel.createdAt.desc(), productModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        Long total = queryFactory.select(productModel.count())
            .from(productModel)
            .fetchOne();
        return PageResult.of(items, query, total == null ? 0 : total);
    }

    @Override
    public Optional<ProductView.Admin> find(Long productId) {
        return Optional.ofNullable(selectJoined(admin())
            .where(productModel.id.eq(productId))
            .fetchOne());
    }

    private <T> JPAQuery<T> selectJoined(Expression<T> projection) {
        return queryFactory.select(projection)
            .from(productModel)
            .join(brandModel).on(brandModel.id.eq(productModel.brandId))
            .leftJoin(productLikeModel).on(productLikeModel.productId.eq(productModel.id))
            .groupBy(productModel.id, brandModel.id);
    }

    private static Expression<ProductView.Summary> summary() {
        return Projections.constructor(ProductView.Summary.class,
            productModel.id, productModel.name, productModel.price, brandModel.id, brandModel.name, LIKE_COUNT);
    }

    private static Expression<ProductView.Admin> admin() {
        return Projections.constructor(ProductView.Admin.class,
            productModel.id, productModel.name, productModel.price, productModel.stock, brandModel.id, brandModel.name, LIKE_COUNT,
            new CaseBuilder().when(productModel.deletedAt.isNotNull()).then(true).otherwise(false));
    }
}
