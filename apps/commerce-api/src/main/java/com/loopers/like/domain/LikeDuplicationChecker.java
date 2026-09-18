package com.loopers.like.domain;

public class LikeDuplicationChecker {

    private final LikeRepository likeRepository;

    public LikeDuplicationChecker(LikeRepository likeRepository) {
        this.likeRepository = likeRepository;
    }

    public boolean isDuplicated(Long userId, Long productId) {
        return likeRepository.findByUserIdAndProductId(userId, productId).isPresent();
    }
}
