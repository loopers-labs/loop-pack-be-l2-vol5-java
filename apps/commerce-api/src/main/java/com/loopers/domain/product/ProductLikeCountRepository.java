package com.loopers.domain.product;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface ProductLikeCountRepository {
    ProductLikeCount save(ProductLikeCount likeCount);

    /** 읽지 않고 한 문장으로 1 늘리고 바뀐 행 수를 돌려준다. 행 잠금은 이 문장부터 commit 까지다 */
    int increase(Long productId, ZonedDateTime updatedAt);

    /** 읽지 않고 한 문장으로 1 줄이고 바뀐 행 수를 돌려준다 */
    int decrease(Long productId, ZonedDateTime updatedAt);

    Optional<Long> findLikeCount(Long productId);
}
