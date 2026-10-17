package com.loopers.domain.like;

/** 좋아요 관계. 좋아요 수는 관계가 아니라 {@link ProductLikeCountRepository} 에서 읽는다. */
public interface LikeRepository {
    Like save(Like like);

    boolean exists(Long userId, Long productId);

    /** 관계를 지우고 삭제된 행 수를 반환한다. 관계가 없으면 0이다. */
    int delete(Long userId, Long productId);
}
