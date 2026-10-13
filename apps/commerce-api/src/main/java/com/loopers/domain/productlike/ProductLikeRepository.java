package com.loopers.domain.productlike;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** TB-04 좋아요 관계와 TB-08 좋아요 수를 함께 맡는다 (AG-04, DR-36). */
public interface ProductLikeRepository {
    ProductLikeModel save(ProductLikeModel like);

    Optional<ProductLikeModel> find(Long userId, Long productId);

    /** 쌍 조건으로 지우고 지운 행 수(0 또는 1)를 돌려준다. */
    int delete(Long userId, Long productId);

    ProductStatModel saveStat(ProductStatModel stat);

    Optional<ProductStatModel> findStat(Long productId);

    /** like_count = like_count + 1. 대상 행이 없으면 IllegalStateException. */
    void increaseLikeCount(Long productId);

    /** like_count = like_count - 1. 대상 행이 없으면 IllegalStateException. */
    void decreaseLikeCount(Long productId);

    /** 삭제되지 않은 행의 deleted_at 을 채운다. */
    void deleteStat(Long productId);

    /** 브랜드의 삭제되지 않은 행 전부의 deleted_at 을 채운다. */
    void deleteStatsOfBrand(Long brandId);

    /** INV-05 좋아요 수. 행이 없는 상품 ID 는 결과에 없다. */
    Map<Long, Long> findLikeCounts(Collection<Long> productIds);
}
