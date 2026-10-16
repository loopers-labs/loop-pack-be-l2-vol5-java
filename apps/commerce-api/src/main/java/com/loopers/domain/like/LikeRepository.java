package com.loopers.domain.like;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface LikeRepository {
    Like save(Like like);

    Optional<Like> find(Long userId, Long productId);

    /** 사용자의 관계를 한 문장으로 지우고 지운 행 수를 돌려준다. 동시에 지우면 늦은 쪽은 0 이다 */
    int delete(Long userId, Long productId);

    /** 사용자의 관계 중 삭제되지 않은 상품만, 좋아요 시각 desc, 식별자 desc 로 조회한다. */
    Page<LikedProduct> findLikedProducts(Long userId, Pageable pageable);
}
