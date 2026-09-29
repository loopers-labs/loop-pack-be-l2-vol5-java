package com.loopers.domain.like;

public record ProductLike(Long userId, Long productId) {

    public ProductLike {
        if (userId == null || productId == null) {
            throw new IllegalArgumentException("userId 와 productId 는 필수입니다");
        }
    }

    public static ProductLike of(Long userId, Long productId) {
        return new ProductLike(userId, productId);
    }
}
