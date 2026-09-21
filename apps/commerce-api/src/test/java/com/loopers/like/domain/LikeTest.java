package com.loopers.like.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LikeTest {

    @DisplayName("[INV-25] 좋아요는 등록한 고객의 것이다.")
    @Nested
    class OwnedByRegisteringUser {

        @DisplayName("[동등 클래스 분할] 좋아요는 등록한 고객과 상품을 기억한다.")
        @Test
        void storesUserAndProduct() {
            // act
            Like like = new Like(1L, 2L);

            // assert
            assertAll(
                () -> assertThat(like.getUserId()).isEqualTo(1L),
                () -> assertThat(like.getProductId()).isEqualTo(2L)
            );
        }

        @DisplayName("[동등 클래스 분할] 소유자가 취소하면 취소된 상태가 된다.")
        @Test
        void cancels_whenRequesterOwnsLike() {
            // arrange
            Like like = new Like(1L, 2L);

            // act
            like.cancel(1L);

            // assert
            assertThat(like.isCanceled()).isTrue();
        }

        @DisplayName("[동등 클래스 분할] 다른 고객이 취소하면 없는 좋아요로 거절하고, 취소되지 않은 상태 그대로다.")
        @Test
        void throwsLikeNotFound_whenRequesterDoesNotOwnLike() {
            // arrange
            Like like = new Like(1L, 2L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> like.cancel(3L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.LIKE_NOT_FOUND),
                () -> assertThat(like.isCanceled()).isFalse()
            );
        }

        @DisplayName("[동등 클래스 분할] 다른 고객이 되살리면 없는 좋아요로 거절하고, 취소된 상태 그대로다.")
        @Test
        void throwsLikeNotFound_whenRequesterDoesNotOwnCanceledLike() {
            // arrange
            Like like = new Like(1L, 2L);
            like.cancel(1L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> like.reregister(3L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.LIKE_NOT_FOUND),
                () -> assertThat(like.isCanceled()).isTrue()
            );
        }
    }
}
