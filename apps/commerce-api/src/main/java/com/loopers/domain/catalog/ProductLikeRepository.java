package com.loopers.domain.catalog;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface ProductLikeRepository {
    ProductLikeModel save(ProductLikeModel like);

    Optional<ProductLikeModel> find(Long userId, Long productId);

    void delete(ProductLikeModel like);

    /** INV-05 좋아요 수 = 관계 개수. 없는 상품 ID 는 0. */
    Map<Long, Long> countByProductIds(Collection<Long> productIds);

    /** FR-LIKE-03: 요청자의 관계 중 상품이 삭제되지 않은 것만, 등록 최신순. */
    PageResult<ProductLikeModel> findPageByUserIdWithActiveProduct(Long userId, PageQuery query);
}
