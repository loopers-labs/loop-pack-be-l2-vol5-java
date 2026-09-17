package com.loopers.infrastructure.catalog;

import com.loopers.domain.catalog.ProductModel;
import com.loopers.domain.catalog.ProductRepository;
import com.loopers.domain.catalog.ProductSort;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.catalog.QProductLikeModel.productLikeModel;
import static com.loopers.domain.catalog.QProductModel.productModel;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<ProductModel> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return productJpaRepository.findById(id);
    }

    @Override
    public List<ProductModel> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return productJpaRepository.findAllById(ids);
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        return productJpaRepository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }

    /**
     * 설계 3-7-A: latest = created_at desc / price_asc = price asc / likes_desc = count(product_like) desc.
     * 동률 보조 기준은 셋 다 id desc (ASM-08). likes_desc 는 같은 BC 의 TB-04 와 조인+집계 (DR-02).
     */
    @Override
    public PageResult<ProductModel> findActivePage(ProductSort sort, PageQuery query) {
        JPAQuery<ProductModel> select = queryFactory.selectFrom(productModel)
            .where(productModel.deletedAt.isNull());
        OrderSpecifier<?> primary = switch (sort) {
            case LATEST -> productModel.createdAt.desc();
            case PRICE_ASC -> productModel.price.asc();
            case LIKES_DESC -> {
                select.leftJoin(productLikeModel).on(productLikeModel.productId.eq(productModel.id))
                    .groupBy(productModel.id);
                yield productLikeModel.id.count().desc();
            }
        };
        List<ProductModel> items = select
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
    public PageResult<ProductModel> findPage(PageQuery query) {
        List<ProductModel> items = queryFactory.selectFrom(productModel)
            .orderBy(productModel.createdAt.desc(), productModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        long total = productJpaRepository.count();
        return PageResult.of(items, query, total);
    }
}
