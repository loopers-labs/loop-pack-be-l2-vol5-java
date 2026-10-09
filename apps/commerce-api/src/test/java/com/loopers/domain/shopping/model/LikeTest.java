package com.loopers.domain.shopping.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LikeTest {

    @DisplayName("생성한 좋아요는 id와 likedAt이 비어 있다")
    @Test
    void create_leavesIdAndLikedAtNull() {
        Like like = Like.create(1L, 10L);

        assertThat(like.getId()).isNull();
        assertThat(like.getUserId()).isEqualTo(1L);
        assertThat(like.getProductId()).isEqualTo(10L);
        assertThat(like.getLikedAt()).isNull();
    }

    @DisplayName("저장된 값으로 좋아요를 복원한다")
    @Test
    void restore_fillsAllValues() {
        Instant likedAt = Instant.parse("2026-01-01T00:00:00Z");

        Like like = Like.restore(5L, 1L, 10L, likedAt);

        assertThat(like.getId()).isEqualTo(5L);
        assertThat(like.getUserId()).isEqualTo(1L);
        assertThat(like.getProductId()).isEqualTo(10L);
        assertThat(like.getLikedAt()).isEqualTo(likedAt);
    }

    @DisplayName("userId가 양수가 아니면 생성할 수 없다")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void create_rejectsNonPositiveUserId(long userId) {
        assertThatThrownBy(() -> Like.create(userId, 10L)).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("productId가 양수가 아니면 생성할 수 없다")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void create_rejectsNonPositiveProductId(long productId) {
        assertThatThrownBy(() -> Like.create(1L, productId)).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("복원 시 id·userId·productId가 양수가 아니거나 likedAt이 없으면 거절한다")
    @Test
    void restore_rejectsInvalidState() {
        Instant likedAt = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> Like.restore(0L, 1L, 10L, likedAt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Like.restore(5L, 0L, 10L, likedAt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Like.restore(5L, 1L, 0L, likedAt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Like.restore(5L, 1L, 10L, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
