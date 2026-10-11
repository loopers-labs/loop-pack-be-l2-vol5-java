package com.loopers.infrastructure.productlike.query;

import com.loopers.application.productlike.query.ProductLikeQueryRepository;
import com.loopers.application.productlike.query.ProductLikeView;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.loopers.domain.brand.QBrandModel.brandModel;
import static com.loopers.domain.product.QProductModel.productModel;
import static com.loopers.domain.productlike.QProductLikeModel.productLikeModel;

@RequiredArgsConstructor
@Component
public class ProductLikeQueryRepositoryImpl implements ProductLikeQueryRepository {
    private final JPAQueryFactory queryFactory;

    /**
     * 설계 3-7-A FR-LIKE-03: TB-04.user_id, TB-03.deleted_at IS NULL, TB-04.created_at desc, id desc.
     * TB-03·TB-02 는 같은 BC 라 한 쿼리로 조인한다 (DR-31). 브랜드는 소프트 삭제만 되므로 inner join.
     */
    @Override
    public PageResult<ProductLikeView.Item> findPageByUserId(Long userId, PageQuery query) {
        List<ProductLikeView.Item> items = queryFactory
            .select(Projections.constructor(ProductLikeView.Item.class,
                productLikeModel.createdAt, productModel.id, productModel.name, productModel.price, brandModel.id, brandModel.name))
            .from(productLikeModel)
            .join(productModel).on(productModel.id.eq(productLikeModel.productId))
            .join(brandModel).on(brandModel.id.eq(productModel.brandId))
            .where(productLikeModel.userId.eq(userId), productModel.deletedAt.isNull())
            .orderBy(productLikeModel.createdAt.desc(), productLikeModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        Long total = queryFactory.select(productLikeModel.count())
            .from(productLikeModel)
            .join(productModel).on(productModel.id.eq(productLikeModel.productId))
            .where(productLikeModel.userId.eq(userId), productModel.deletedAt.isNull())
            .fetchOne();
        return PageResult.of(items, query, total == null ? 0 : total);
    }
}
