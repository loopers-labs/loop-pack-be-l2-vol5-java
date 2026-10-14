package com.loopers.product.adapter.out.persistence;

import com.loopers.like.domain.QLikeModel;
import com.loopers.product.application.port.out.ProductQueryPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.ProductSort;
import com.loopers.product.domain.ProductWithLikes;
import com.loopers.product.domain.QProductModel;
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 고객 상품 조회 (ADR-02). 좋아요 수는 likes 관계를 집계해 상품과 함께 돌려주고, 같은 값으로 정렬한다 (LIK-04).
 */
@RequiredArgsConstructor
@Component
public class ProductQueryPersistenceAdapter implements ProductQueryPort {

    private static final QProductModel PRODUCT = QProductModel.productModel;
    private static final QLikeModel LIKE = QLikeModel.likeModel;
    private static final NumberExpression<Long> LIKE_COUNT = LIKE.id.count();

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<ProductWithLikes> findSellable(Long brandId, ProductSort sort, Pageable pageable) {
        List<ProductWithLikes> content = sort == ProductSort.LIKES_DESC
            ? findPageSortedByLikes(brandId, pageable)
            : findPageThenCountLikes(brandId, sort, pageable);

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

    /**
     * likes_desc: 정렬 기준이 집계 결과라, 전체 후보의 좋아요를 센 뒤에야 페이지를 고를 수 있다 (W4 측정: 상품 1만·좋아요 9만 행을 모두 읽는다).
     */
    private List<ProductWithLikes> findPageSortedByLikes(Long brandId, Pageable pageable) {
        return selectWithLikes()
            .where(PRODUCT.deletedAt.isNull(), brandIdEq(brandId))
            .groupBy(PRODUCT.id)
            .orderBy(orderOf(ProductSort.LIKES_DESC))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch()
            .stream()
            .map(this::toProductWithLikes)
            .toList();
    }

    /**
     * latest·price_asc: 정렬에 좋아요가 필요 없으므로 인덱스 순서로 상품 페이지를 먼저 고르고, 그 상품들의 좋아요만 한 번에 센다.
     * 한 쿼리로 JOIN·GROUP BY하면 브랜드의 모든 상품 좋아요를 센 뒤 임시 테이블에서 정렬한다 (W4 측정, 인덱스를 맞춰도 남음).
     */
    private List<ProductWithLikes> findPageThenCountLikes(Long brandId, ProductSort sort, Pageable pageable) {
        List<ProductModel> products = queryFactory.selectFrom(PRODUCT)
            .where(PRODUCT.deletedAt.isNull(), brandIdEq(brandId))
            .orderBy(orderOf(sort))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();
        Map<Long, Long> likeCounts = likeCountsOf(products.stream().map(ProductModel::getId).toList());
        return products.stream()
            .map(product -> new ProductWithLikes(product, likeCounts.getOrDefault(product.getId(), 0L)))
            .toList();
    }

    private Map<Long, Long> likeCountsOf(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return queryFactory.select(LIKE.productId, LIKE_COUNT)
            .from(LIKE)
            .where(LIKE.productId.in(productIds))
            .groupBy(LIKE.productId)
            .fetch()
            .stream()
            .collect(Collectors.toMap(row -> row.get(LIKE.productId), row -> row.get(LIKE_COUNT)));
    }

    @Override
    public Set<Long> findSellableIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(queryFactory.select(PRODUCT.id)
            .from(PRODUCT)
            .where(PRODUCT.id.in(productIds), PRODUCT.deletedAt.isNull())
            .fetch());
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
