package com.loopers.domain.like;

import java.util.List;
import java.util.Map;

public interface LikeRepository {
    long countByProductId(Long productId);

    Map<Long, Long> countByProductIds(List<Long> productIds);
}
