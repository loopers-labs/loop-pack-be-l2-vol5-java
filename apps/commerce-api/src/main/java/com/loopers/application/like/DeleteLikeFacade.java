package com.loopers.application.like;

import com.loopers.application.user.IdentifyUser;
import lombok.RequiredArgsConstructor;
import com.loopers.domain.like.ProductLikeRepository;

@RequiredArgsConstructor
public class DeleteLikeFacade {
    private final IdentifyUser identifyUser;
    private final ProductLikeRepository likes;
    public void delete(Long userId, long productId) {
        long id = identifyUser.require(userId);
        likes.delete(id, productId);
    }
}
