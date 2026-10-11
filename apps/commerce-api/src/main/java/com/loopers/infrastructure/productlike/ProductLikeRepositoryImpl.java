package com.loopers.infrastructure.productlike;

import com.loopers.domain.productlike.ProductLikeModel;
import com.loopers.domain.productlike.ProductLikeRepository;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.loopers.domain.productlike.QProductLikeModel.productLikeModel;

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
}
