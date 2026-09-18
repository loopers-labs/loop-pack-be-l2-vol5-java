package com.loopers.infrastructure.product;

import com.loopers.domain.brand.QBrand;
import com.loopers.domain.like.QLike;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import com.loopers.domain.product.ProductWithBrand;
import com.loopers.domain.product.QProduct;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
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

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private static final QProduct PRODUCT = QProduct.product;
    private static final QBrand BRAND = QBrand.brand;
    private static final QLike LIKE = QLike.like;
    private static final NumberExpression<Long> LIKE_COUNT = LIKE.id.count();

    private final ProductJpaRepository productJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Product save(Product product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<Product> findActive(Long productId) {
        return productJpaRepository.findByIdAndDeletedAtIsNull(productId);
    }

    @Override
    public Optional<ProductWithBrand> findActiveWithBrand(Long productId) {
        return productJpaRepository.findActiveWithBrand(productId);
    }

    @Override
    public Page<ProductWithBrand> findActiveWithBrand(Long brandId, Pageable pageable) {
        return productJpaRepository.findActiveWithBrand(brandId, pageable);
    }

    @Override
    public Optional<ProductView> findActiveView(Long productId) {
        return Optional.ofNullable(selectViews().where(PRODUCT.deletedAt.isNull(), PRODUCT.id.eq(productId)).fetchOne());
    }

    @Override
    public Page<ProductView> findActiveViews(Long brandId, ProductSort sort, Pageable pageable) {
        List<ProductView> content = selectViews()
            .where(PRODUCT.deletedAt.isNull(), brandEq(brandId))
            .orderBy(orderOf(sort))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();
        Long total = queryFactory.select(PRODUCT.count())
            .from(PRODUCT)
            .where(PRODUCT.deletedAt.isNull(), brandEq(brandId))
            .fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    // 좋아요가 없는 상품도 빠지지 않도록 outer join 으로 센다 (설계 6.2)
    private JPAQuery<ProductView> selectViews() {
        return queryFactory
            .select(Projections.constructor(ProductView.class,
                PRODUCT.id, PRODUCT.name, PRODUCT.price, PRODUCT.stock, LIKE_COUNT,
                BRAND.id, BRAND.name, BRAND.description))
            .from(PRODUCT)
            .join(PRODUCT.brand, BRAND)
            .leftJoin(LIKE).on(LIKE.productId.eq(PRODUCT.id))
            .groupBy(PRODUCT.id, PRODUCT.name, PRODUCT.price, PRODUCT.stock, BRAND.id, BRAND.name, BRAND.description);
    }

    private static BooleanExpression brandEq(Long brandId) {
        return brandId == null ? null : PRODUCT.brand.id.eq(brandId);
    }

    private static OrderSpecifier<?>[] orderOf(ProductSort sort) {
        OrderSpecifier<?> primary = switch (sort) {
            case LATEST -> PRODUCT.createdAt.desc();
            case PRICE_ASC -> PRODUCT.price.asc();
            case LIKES_DESC -> LIKE_COUNT.desc();
        };
        return new OrderSpecifier<?>[] {primary, PRODUCT.id.desc()};
    }
}
