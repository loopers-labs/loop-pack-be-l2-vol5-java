package com.loopers.like.app.port;

import com.loopers.like.domain.Like;
import com.loopers.product.domain.ProductId;
import java.util.List;

public interface LikeRepository {
    // 관계가 새로 생기면 true, 이미 있으면 false
    boolean save(Like like);

    // 관계를 실제로 지웠으면 true, 없었으면 false
    boolean delete(Like like);

    List<ProductId> findActiveProductIds(long userId, int page, int size);
}
