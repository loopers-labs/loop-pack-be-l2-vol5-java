package com.loopers.domain.shopping.model;

import java.time.Instant;

// 좋아요 도메인 모델
public final class Like {
    private final Long id;
    private final long userId;
    private final long productId;
    private final Instant likedAt;

    private Like(Long id, long userId, long productId, Instant likedAt) {
        if (userId <= 0 || productId <= 0) {
            throw new IllegalArgumentException("좋아요의 사용자 ID와 상품 ID는 양수여야 합니다.");
        }
        this.id = id;
        this.userId = userId;
        this.productId = productId;
        this.likedAt = likedAt;
    }

    public static Like create(long userId, long productId) {
        return new Like(null, userId, productId, null);
    }

    // 저장된 값으로 좋아요 복원
    public static Like restore(long id, long userId, long productId, Instant likedAt) {
        if (id <= 0 || likedAt == null) {
            throw new IllegalArgumentException("저장된 좋아요 상태가 올바르지 않습니다.");
        }
        return new Like(id, userId, productId, likedAt);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public long getProductId() {
        return productId;
    }

    public Instant getLikedAt() {
        return likedAt;
    }
}
