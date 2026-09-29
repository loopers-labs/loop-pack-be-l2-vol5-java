package com.loopers.application.like;

import com.loopers.domain.like.LikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LikeFacade {

    private final LikeService likeService;
    @Transactional
    public long like(Long userId, Long productId) {
        likeService.like(userId, productId);
        return likeService.countOf(productId);
    }

    @Transactional
    public long unlike(Long userId, Long productId) {
        likeService.unlike(userId, productId);
        return likeService.countOf(productId);
    }
}
