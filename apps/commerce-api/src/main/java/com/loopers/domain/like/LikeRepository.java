package com.loopers.domain.like;

import java.util.Optional;

public interface LikeRepository {
    Optional<LikeModel> find(Long userId, Long productId);

    boolean exists(Long userId, Long productId);

    LikeModel save(LikeModel like);

    /** 좋아요 관계는 취소하면 더 이상 존재하지 않으므로 물리 삭제한다. */
    void delete(LikeModel like);
}
