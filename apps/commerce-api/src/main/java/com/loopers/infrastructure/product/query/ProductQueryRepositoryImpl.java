package com.loopers.infrastructure.product.query;

import com.loopers.application.product.query.ProductQueryRepository;
import com.loopers.application.product.query.ProductView;
import com.loopers.domain.product.ProductSort;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.loopers.domain.brand.QBrandModel.brandModel;
import static com.loopers.domain.product.QProductModel.productModel;
import static com.loopers.domain.productlike.QProductLikeModel.productLikeModel;

@RequiredArgsConstructor
@Component
public class ProductQueryRepositoryImpl implements ProductQueryRepository {
    private final JPAQueryFactory queryFactory;

    /**
     * 설계 3-7-A: latest = created_at desc / price_asc = price asc / likes_desc = count(product_like) desc.
     * 동률 보조 기준은 셋 다 id desc (ASM-08). TB-02·TB-04 는 같은 BC 라 한 쿼리로 조인+집계 (DR-02, DR-31).
     * 삭제되지 않은 상품의 브랜드는 삭제될 수 없으므로 (INV-10) brand 는 inner join.
     */
    @Override
    public PageResult<ProductView.Summary> findActivePage(ProductSort sort, PageQuery query) {
        NumberExpression<Long> likeCount = productLikeModel.id.count();
        OrderSpecifier<?> primary = switch (sort) {
            case LATEST -> productModel.createdAt.desc();
            case PRICE_ASC -> productModel.price.asc();
            case LIKES_DESC -> likeCount.desc();
        };
        List<ProductView.Summary> items = queryFactory
            .select(Projections.constructor(ProductView.Summary.class,
                productModel.id, productModel.name, productModel.price, brandModel.id, brandModel.name, likeCount))
            .from(productModel)
            .join(brandModel).on(brandModel.id.eq(productModel.brandId))
            .leftJoin(productLikeModel).on(productLikeModel.productId.eq(productModel.id))
            .where(productModel.deletedAt.isNull())
            .groupBy(productModel.id, brandModel.id)
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
}
