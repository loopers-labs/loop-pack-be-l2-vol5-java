package com.loopers.infrastructure.product;

import com.loopers.domain.brand.QBrand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import com.loopers.domain.product.ProductWithBrand;
import com.loopers.domain.product.QProduct;
import com.loopers.domain.product.QProductLikeCount;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private static final QProduct PRODUCT = QProduct.product;
    private static final QBrand BRAND = QBrand.brand;
    private static final QProductLikeCount LIKE_COUNT = QProductLikeCount.productLikeCount;

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
    public Optional<Product> findActiveForUpdate(Long productId) {
        return productJpaRepository.findActiveForUpdate(productId);
    }

    @Override
    public List<Product> findAllActiveForUpdate(Collection<Long> productIds) {
        return productJpaRepository.findAllActiveForUpdate(productIds);
    }

    @Override
    public Set<Long> findActiveIds(Collection<Long> productIds) {
        return new HashSet<>(productJpaRepository.findActiveIds(productIds));
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

    @Override
    public int deleteAllOfBrand(Long brandId, ZonedDateTime deletedAt) {
        return productJpaRepository.deleteAllOfBrand(brandId, deletedAt);
    }

    // 좋아요 수는 관계를 집계하지 않고 좋아요 수 행에서 읽는다. 모든 상품이 행을 가지므로 inner join 이다 (4주차 설계 2.2, 4.1)
    private JPAQuery<ProductView> selectViews() {
        return queryFactory
            .select(Projections.constructor(ProductView.class,
                PRODUCT.id, PRODUCT.name, PRODUCT.price, PRODUCT.stock, LIKE_COUNT.likeCount,
                BRAND.id, BRAND.name, BRAND.description))
            .from(PRODUCT)
            .join(PRODUCT.brand, BRAND)
            .join(LIKE_COUNT).on(LIKE_COUNT.productId.eq(PRODUCT.id));
    }

    private static BooleanExpression brandEq(Long brandId) {
        return brandId == null ? null : PRODUCT.brand.id.eq(brandId);
    }

    // 좋아요순의 동점은 좋아요 수 행의 상품 식별자로 가른다. 값은 상품 식별자와 같고, (like_count, product_id) 인덱스 순서와 맞는다
    private static OrderSpecifier<?>[] orderOf(ProductSort sort) {
        return switch (sort) {
            case LATEST -> new OrderSpecifier<?>[] {PRODUCT.createdAt.desc(), PRODUCT.id.desc()};
            case PRICE_ASC -> new OrderSpecifier<?>[] {PRODUCT.price.asc(), PRODUCT.id.desc()};
            case LIKES_DESC -> new OrderSpecifier<?>[] {LIKE_COUNT.likeCount.desc(), LIKE_COUNT.productId.desc()};
        };
    }
}
