package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.QProductModel;
import com.loopers.infrastructure.support.QueryDslSort;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private static final QProductModel PRODUCT = QProductModel.productModel;

    private final ProductJpaRepository productJpaRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 즉시 flush해 @PrePersist·@PreUpdate(생성·수정 시각)가 응답을 만들기 전에 반영되게 한다.
     */
    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.saveAndFlush(product);
    }

    @Override
    public Optional<ProductModel> findActiveById(Long id) {
        ProductModel product = queryFactory.selectFrom(PRODUCT)
            .where(PRODUCT.id.eq(id), PRODUCT.deletedAt.isNull())
            .fetchOne();
        return Optional.ofNullable(product);
    }

    /**
     * 목록과 개수를 따로 센다. brandId가 null이면 브랜드 조건을 붙이지 않는다.
     * 정렬은 호출자가 넘긴 Sort를 그대로 쓴다 (관리자 목록은 id desc).
     */
    @Override
    public Page<ProductModel> findActive(Long brandId, Pageable pageable) {
        List<ProductModel> content = queryFactory.selectFrom(PRODUCT)
            .where(PRODUCT.deletedAt.isNull(), brandIdEq(brandId))
            .orderBy(QueryDslSort.of(pageable.getSort(), PRODUCT))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

        Long total = queryFactory.select(PRODUCT.count())
            .from(PRODUCT)
            .where(PRODUCT.deletedAt.isNull(), brandIdEq(brandId))
            .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        Integer one = queryFactory.selectOne()
            .from(PRODUCT)
            .where(PRODUCT.brandId.eq(brandId), PRODUCT.deletedAt.isNull())
            .fetchFirst();
        return one != null;
    }

    @Override
    public List<ProductModel> findAllByIds(Collection<Long> ids) {
        return productJpaRepository.findAllById(ids);
    }

    private BooleanExpression brandIdEq(Long brandId) {
        return brandId == null ? null : PRODUCT.brandId.eq(brandId);
    }
}
