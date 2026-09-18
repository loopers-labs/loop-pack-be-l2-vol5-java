package com.loopers.domain.like;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface LikeRepository {
    Like save(Like like);

    Optional<Like> find(Long userId, Long productId);

    void delete(Like like);

    long countByProductId(Long productId);

    /** 사용자의 관계 중 삭제되지 않은 상품만, 좋아요 시각 desc, 식별자 desc 로 조회한다. */
    Page<LikedProduct> findLikedProducts(Long userId, Pageable pageable);
}
