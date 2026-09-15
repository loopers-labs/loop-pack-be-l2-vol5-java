package com.loopers.domain.like;

import java.time.ZonedDateTime;
import java.util.List;

public interface LikeRepository {

    /**
     * LIK-01·LIK-02: 관계가 없으면 추가하고, 있으면 아무것도 하지 않는다. 동시에 와도 행은 하나다 (ADR-13).
     */
    void addIfAbsent(Long userId, Long productId, ZonedDateTime likedAt);

    /**
     * LIK-02: 관계가 있으면 지우고, 없으면 아무것도 하지 않는다.
     */
    void remove(Long userId, Long productId);

    /**
     * 사용자의 좋아요 관계를 최근에 누른 순서로 돌려준다.
     */
    List<LikeModel> findAllByUserIdNewestFirst(Long userId);
}
