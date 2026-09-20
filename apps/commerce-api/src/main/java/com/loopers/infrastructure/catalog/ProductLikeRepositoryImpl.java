package com.loopers.infrastructure.catalog;

import com.loopers.domain.catalog.ProductLikeModel;
import com.loopers.domain.catalog.ProductLikeRepository;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.loopers.domain.catalog.QProductLikeModel.productLikeModel;
import static com.loopers.domain.catalog.QProductModel.productModel;

@RequiredArgsConstructor
@Component
public class ProductLikeRepositoryImpl implements ProductLikeRepository {
    private final ProductLikeJpaRepository productLikeJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public ProductLikeModel save(ProductLikeModel like) {
        return productLikeJpaRepository.save(like);
    }

    @Override
    public Optional<ProductLikeModel> find(Long userId, Long productId) {
        return productLikeJpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public void delete(ProductLikeModel like) {
        productLikeJpaRepository.delete(like);
    }

    @Override
    public Map<Long, Long> countByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<Tuple> rows = queryFactory.select(productLikeModel.productId, productLikeModel.id.count())
            .from(productLikeModel)
            .where(productLikeModel.productId.in(productIds))
            .groupBy(productLikeModel.productId)
            .fetch();
        return rows.stream().collect(Collectors.toMap(
            row -> row.get(productLikeModel.productId),
            row -> {
                Long count = row.get(productLikeModel.id.count());
                return count == null ? 0L : count;
            }));
    }

    /** 설계 3-7-A FR-LIKE-03: TB-04.user_id, TB-03.deleted_at IS NULL, TB-04.created_at desc, id desc. */
    @Override
    public PageResult<ProductLikeModel> findPageByUserIdWithActiveProduct(Long userId, PageQuery query) {
        List<ProductLikeModel> items = queryFactory.selectFrom(productLikeModel)
            .join(productModel).on(productModel.id.eq(productLikeModel.productId))
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
