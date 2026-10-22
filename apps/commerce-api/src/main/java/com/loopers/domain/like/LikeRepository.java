package com.loopers.domain.like;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LikeRepository {

    Optional<Like> findByUserIdAndProductId(Long userId, Long productId);

    List<Like> findAllByUserId(Long userId);

    long countByProductId(Long productId);

    Map<Long, Long> countByProductIds(List<Long> productIds);

    // 관계를 새로 저장한 경우에만 true를 반환한다.
    boolean insertIfAbsent(Like like);

    void delete(Like like);
}
