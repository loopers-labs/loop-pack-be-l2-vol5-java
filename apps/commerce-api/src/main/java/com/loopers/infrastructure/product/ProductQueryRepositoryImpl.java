package com.loopers.infrastructure.product;

import com.loopers.domain.like.QLikeModel;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductWithLikes;
import com.loopers.domain.product.QProductModel;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 고객 상품 조회 (ADR-02). 좋아요 수는 likes 관계를 집계해 상품과 함께 돌려주고, 같은 값으로 정렬한다 (LIK-04).
 */
@RequiredArgsConstructor
@Component
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final QProductModel PRODUCT = QProductModel.productModel;
    private static final QLikeModel LIKE = QLikeModel.likeModel;
    private static final NumberExpression<Long> LIKE_COUNT = LIKE.id.count();

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<ProductWithLikes> findSellable(Long brandId, ProductSort sort, Pageable pageable) {
        List<ProductWithLikes> content = selectWithLikes()
            .where(PRODUCT.deletedAt.isNull(), brandIdEq(brandId))
            .groupBy(PRODUCT.id)
            .orderBy(orderOf(sort))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch()
            .stream()
            .map(this::toProductWithLikes)
            .toList();

        // 개수는 집계 없이 상품만 센다 (GROUP BY 결과에 개수를 붙이면 totalElements가 틀린다).
        Long total = queryFactory.select(PRODUCT.count())
            .from(PRODUCT)
            .where(PRODUCT.deletedAt.isNull(), brandIdEq(brandId))
            .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public Optional<ProductWithLikes> findSellableById(Long productId) {
        Tuple row = selectWithLikes()
            .where(PRODUCT.id.eq(productId), PRODUCT.deletedAt.isNull())
            .groupBy(PRODUCT.id)
            .fetchOne();
        return Optional.ofNullable(row).map(this::toProductWithLikes);
    }

    private JPAQuery<Tuple> selectWithLikes() {
        return queryFactory.select(PRODUCT, LIKE_COUNT)
            .from(PRODUCT)
            .leftJoin(LIKE).on(LIKE.productId.eq(PRODUCT.id));
    }

    private ProductWithLikes toProductWithLikes(Tuple row) {
        Long likeCount = row.get(LIKE_COUNT);
        return new ProductWithLikes(row.get(PRODUCT), likeCount == null ? 0 : likeCount);
    }

    private BooleanExpression brandIdEq(Long brandId) {
        return brandId == null ? null : PRODUCT.brandId.eq(brandId);
    }

    private OrderSpecifier<?>[] orderOf(ProductSort sort) {
        return switch (sort) {
            case LATEST -> new OrderSpecifier<?>[] {PRODUCT.id.desc()};
            case PRICE_ASC -> new OrderSpecifier<?>[] {PRODUCT.price.asc(), PRODUCT.id.desc()};
            case LIKES_DESC -> new OrderSpecifier<?>[] {LIKE_COUNT.desc(), PRODUCT.id.desc()};
        };
    }
}
