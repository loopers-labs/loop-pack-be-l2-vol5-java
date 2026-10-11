package com.loopers.domain.productlike;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface ProductLikeRepository {
    ProductLikeModel save(ProductLikeModel like);

    Optional<ProductLikeModel> find(Long userId, Long productId);

    void delete(ProductLikeModel like);

    /** INV-05 좋아요 수 = 관계 개수. 없는 상품 ID 는 0. */
    Map<Long, Long> countByProductIds(Collection<Long> productIds);
}
