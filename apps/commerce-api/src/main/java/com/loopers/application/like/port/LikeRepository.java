package com.loopers.application.like.port;

import com.loopers.domain.like.Like;
import com.loopers.domain.product.ProductId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface LikeRepository {
    void save(Like like);
    void delete(Like like);
    Map<ProductId, Long> countByProductIds(Collection<ProductId> ids);
    List<ProductId> findActiveProductIds(long userId, int page, int size);
}
