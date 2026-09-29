package com.loopers.domain.like;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LikeRepository {
    Like save(Like like);

    void delete(Like like);

    Optional<Like> findByUserIdAndProductId(Long userId, Long productId);

    List<Like> findAllByUserId(Long userId);

    long countByProductId(Long productId);

    /**
     * 여러 상품의 좋아요 수를 한 번에 집계한다.
     * 좋아요가 없는 상품은 결과에 포함되지 않는다.
     */
    Map<Long, Long> countByProductIds(Collection<Long> productIds);
}
