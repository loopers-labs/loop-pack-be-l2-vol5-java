package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSortType;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.like.QLike.like;
import static com.loopers.domain.product.QProduct.product;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Product save(Product product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<Product> findById(Long id) {
        return productJpaRepository.findById(id);
    }

    @Override
    public List<Product> findAllByIdIn(Collection<Long> ids) {
        return productJpaRepository.findAllByIdIn(ids);
    }

    @Override
    public List<Product> findAll(Long brandId, ProductSortType sortType, int page, int size) {
        return queryFactory
            .selectFrom(product)
            .where(product.deletedAt.isNull(), brandIdEq(brandId))
            .orderBy(primaryOrder(sortType), product.id.desc())
            .offset((long) page * size)
            .limit(size)
            .fetch();
    }

    @Override
    public List<Product> findAllForAdmin(Long brandId, int page, int size) {
        return queryFactory
            .selectFrom(product)
            .where(brandIdEq(brandId))
            .orderBy(product.id.desc())
            .offset((long) page * size)
            .limit(size)
            .fetch();
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        Integer found = queryFactory
            .selectOne()
            .from(product)
            .where(product.brandId.eq(brandId), product.deletedAt.isNull())
            .fetchFirst();

        return found != null;
    }

    private BooleanExpression brandIdEq(Long brandId) {
        return brandId == null ? null : product.brandId.eq(brandId);
    }

    private OrderSpecifier<?> primaryOrder(ProductSortType sortType) {
        return switch (sortType) {
            case LATEST -> product.createdAt.desc();
            case PRICE_ASC -> product.price.amount.asc();
            case LIKES_DESC -> new OrderSpecifier<>(Order.DESC, likeCount());
        };
    }

    /**
     * 좋아요 수는 관계 테이블에서 집계한다.
     */
    private Expression<Long> likeCount() {
        return JPAExpressions.select(like.count())
            .from(like)
            .where(like.productId.eq(product.id));
    }
}
